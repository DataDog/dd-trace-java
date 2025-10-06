package datadog.trace.instrumentation.jetty9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(InstrumenterModule.class)
public final class JettyCommitResponseInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("org.eclipse.jetty.server.HttpChannel")
          .withMethod(
              new String[0],
              Reference.EXPECTS_PUBLIC_OR_PROTECTED | Reference.EXPECTS_NON_STATIC,
              "commitResponse",
              "Z",
              "Lorg/eclipse/jetty/http/HttpGenerator$ResponseInfo;",
              "Ljava/nio/ByteBuffer;",
              "Z")
          .build()
    };
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".RequestURIDataAdapter",
      "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public String muzzleDirective() {
    return "before_904";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("commitResponse")
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.eclipse.jetty.http.HttpGenerator$ResponseInfo")))
            .and(takesArgument(1, named("java.nio.ByteBuffer")))
            .and(takesArgument(2, boolean.class)),
        JettyCommitResponseInstrumentation.class.getName() + "$CommitResponseAdvice");
  }

  static class CommitResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip */ before(
        @Advice.This HttpChannel connection,
        @Advice.Argument(0) HttpGenerator.ResponseInfo responseInfo,
        @Advice.FieldValue("_committed") AtomicBoolean _committed) {
      if (responseInfo.getStatus() == 100) {
        return false;
      }
      boolean wasCommitted = !_committed.compareAndSet(false, true);
      if (wasCommitted) {
        return false;
      }

      // henceforth we need to reset _committed to false when we don't want to skip the body

      Request req = connection.getRequest();

      if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
        _committed.set(false);
        return false;
      }

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (!(existingSpan instanceof AgentSpan)) {
        _committed.set(false);
        return false;
      }
      AgentSpan span = (AgentSpan) existingSpan;
      RequestContext requestContext = span.getRequestContext();
      if (requestContext == null) {
        _committed.set(false);
        return false;
      }

      Response resp = connection.getResponse();

      Flow<Void> flow =
          DECORATE.callIGCallbackResponseAndHeaders(
              span, resp, resp.getStatus(), ExtractAdapter.Response.GETTER);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = requestContext.getBlockResponseFunction();
        if (brf != null) {
          _committed.set(false);
          boolean res =
              brf.tryCommitBlockingResponse(
                  requestContext.getTraceSegment(),
                  rba.getStatusCode(),
                  rba.getBlockingContentType(),
                  rba.getExtraHeaders());
          if (res && _committed.get()) {
            requestContext.getTraceSegment().effectivelyBlocked();
            return true;
          }
        }
      }

      _committed.set(false);
      return false;
    }
  }
}
