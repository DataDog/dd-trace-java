package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;
import javax.ws.rs.core.MultivaluedMap;
import net.bytebuddy.asm.Advice;
import org.jboss.resteasy.spi.HttpRequest;

@AutoService(InstrumenterModule.class)
public class MethodExpressionInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MethodExpressionInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.core.registry.MethodExpression";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("populatePathParams")
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.jboss.resteasy.spi.HttpRequest")))
            .and(takesArgument(1, named("java.util.regex.Matcher")))
            .and(takesArgument(2, String.class)),
        MethodExpressionInstrumentation.class.getName() + "$PopulatePathParamsAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class PopulatePathParamsAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Argument(0) HttpRequest req,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }

      MultivaluedMap<String, String> pathParameters = req.getUri().getPathParameters();
      if (pathParameters.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      if (callback == null) {
        return;
      }
      Flow<Void> flow = callback.apply(reqCtx, pathParameters);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          t = new BlockingException("Blocked request (for MethodExpression/populatePathParams)");
          reqCtx.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }
}
