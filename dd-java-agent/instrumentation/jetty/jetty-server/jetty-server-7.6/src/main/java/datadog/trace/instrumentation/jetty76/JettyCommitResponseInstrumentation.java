package datadog.trace.instrumentation.jetty76;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(InstrumenterModule.class)
public final class JettyCommitResponseInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.AbstractHttpConnection";
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
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("org.eclipse.jetty.server.AbstractHttpConnection")
          .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "commitResponse", "V", "Z")
          .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "completeResponse", "V")
          .build(),
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("commitResponse")
            .and(takesArguments(1))
            .and(takesArgument(0, boolean.class))
            .or(named("completeResponse").and(takesArguments(0))),
        JettyCommitResponseInstrumentation.class.getName() + "$CommitResponseAdvice");
  }

  static class CommitResponseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip */ before(@Advice.This AbstractHttpConnection connection) {
      Generator generator = connection.getGenerator();
      if (generator.isCommitted()) {
        return false;
      }

      Request req = connection.getRequest();

      if (req.getAttribute(DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
        return false;
      }

      Object contextObj = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (!(contextObj instanceof Context)) {
        return false;
      }
      Context context = (Context) contextObj;
      AgentSpan span = spanFromContext(context);
      RequestContext requestContext;
      if (span == null || (requestContext = span.getRequestContext()) == null) {
        return false;
      }

      Response resp = connection.getResponse();
      if (resp.getStatus() == 100) {
        return false;
      }

      Flow<Void> flow =
          DECORATE.callIGCallbackResponseAndHeaders(
              span, resp, resp.getStatus(), ExtractAdapter.Response.GETTER);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = requestContext.getBlockResponseFunction();
        if (brf != null) {
          return brf.tryCommitBlockingResponse(
              requestContext.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
        }
      }

      return false;
    }
  }
}
