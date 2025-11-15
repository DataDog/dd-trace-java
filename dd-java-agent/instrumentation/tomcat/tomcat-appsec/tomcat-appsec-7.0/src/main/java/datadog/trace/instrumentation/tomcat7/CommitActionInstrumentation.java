package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.instrumentation.tomcat.ExtractAdapter;
import datadog.trace.instrumentation.tomcat.TomcatDecorator;
import net.bytebuddy.asm.Advice;
import org.apache.coyote.ActionCode;
import org.apache.coyote.ActionHook;
import org.apache.coyote.Request;
import org.apache.coyote.Response;

/** @see org.apache.coyote.ActionHook */
@AutoService(InstrumenterModule.class)
public class CommitActionInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public CommitActionInstrumentation() {
    super("tomcat");
  }

  @Override
  public String muzzleDirective() {
    return "from703";
  }

  @Override
  public String[] knownMatchingTypes() {
    /* we're assuming all these have a coyote.Response response field and implement ActionHook */
    return new String[] {
      "org.apache.coyote.ajp.AbstractAjpProcessor",
      "org.apache.coyote.http11.AbstractHttp11Processor",
      "org.apache.coyote.AbstractProcessor",
      "org.apache.coyote.ajp.AjpAprProcessor",
      "org.apache.coyote.ajp.AjpProcessor",
      "org.apache.coyote.http11.Http11AprProcessor",
      "org.apache.coyote.http11.Http11NioProcessor",
      "org.apache.coyote.http11.Http11Processor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("action"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.coyote.ActionCode")))
            .and(takesArgument(1, Object.class)),
        CommitActionInstrumentation.class.getName() + "$ProcessCommitActionAdvice");
  }

  @Override
  public String[] helperClassNames() {
    String pkg = "datadog.trace.instrumentation.tomcat";
    return new String[] {
      pkg + ".ExtractAdapter",
      pkg + ".ExtractAdapter$Request",
      pkg + ".ExtractAdapter$Response",
      pkg + ".ExtractAdapter$CoyoteResponse",
      pkg + ".TomcatDecorator",
      pkg + ".TomcatDecorator$TomcatBlockResponseFunction",
      pkg + ".TomcatBlockingHelper",
      pkg + ".RequestURIDataAdapter",
    };
  }

  static class ProcessCommitActionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    static boolean /* skip body */ before(
        @Advice.Argument(0) ActionCode actionCode,
        @Advice.This ActionHook thiz,
        @Advice.FieldValue("response") Response coyoteResponse) {
      if (actionCode != ActionCode.COMMIT) {
        return false;
      }
      Request request = coyoteResponse.getRequest();
      if (request.getAttribute(HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE) != null) {
        return false;
      }
      request.setAttribute(HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);

      // on async requests AgentTracer.activeSpan() may return null
      Object contextObj = request.getAttribute(HttpServerDecorator.DD_CONTEXT_ATTRIBUTE);
      if (!(contextObj instanceof Context)) {
        return false;
      }
      Context context = (Context) contextObj;
      AgentSpan agentSpan = spanFromContext(context);
      if (agentSpan == null) {
        return false;
      }
      RequestContext requestContext = agentSpan.getRequestContext();
      if (requestContext == null) {
        return false;
      }

      Flow<Void> flow =
          TomcatDecorator.DECORATE.callIGCallbackResponseAndHeaders(
              agentSpan,
              coyoteResponse,
              coyoteResponse.getStatus(),
              ExtractAdapter.CoyoteResponse.GETTER);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction brf = requestContext.getBlockResponseFunction();
        if (brf != null) {
          brf.tryCommitBlockingResponse(
              requestContext.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          thiz.action(ActionCode.CLOSE, null);
          return true;
        }
      }

      return false;
    }
  }
}
