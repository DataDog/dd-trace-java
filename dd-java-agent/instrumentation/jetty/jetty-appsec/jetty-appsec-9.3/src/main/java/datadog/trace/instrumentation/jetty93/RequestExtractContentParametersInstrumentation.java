package datadog.trace.instrumentation.jetty93;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(InstrumenterModule.class)
public class RequestExtractContentParametersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String MULTI_MAP_INTERNAL_NAME = "Lorg/eclipse/jetty/util/MultiMap;";

  public RequestExtractContentParametersInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("extractContentParameters").and(takesArguments(0)).or(named("getParts")),
        getClass().getName() + "$ExtractContentParametersAdvice");
  }

  private static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withMethod(new String[0], 0, "extractContentParameters", "V")
          .withField(new String[0], 0, "_contentParameters", MULTI_MAP_INTERNAL_NAME)
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE};
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ExtractContentParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(@Advice.FieldValue("_contentParameters") final MultiMap<String> map) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Request.class);
      return callDepth == 0 && map == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.FieldValue("_contentParameters") final MultiMap<String> map,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Request.class);
      if (!proceed) {
        return;
      }
      if (map == null || map.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, map);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
          if (t == null) {
            t = new BlockingException("Blocked request (for Request/extractContentParameters)");
            reqCtx.getTraceSegment().effectivelyBlocked();
          }
        }
      }
    }
  }
}
