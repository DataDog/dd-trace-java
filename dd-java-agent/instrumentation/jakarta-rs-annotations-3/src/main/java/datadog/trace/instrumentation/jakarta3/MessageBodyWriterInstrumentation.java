package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
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
import jakarta.ws.rs.core.MediaType;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MessageBodyWriterInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public MessageBodyWriterInstrumentation() {
    super("jakarta-rs");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.ws.rs.ext.MessageBodyWriter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("writeTo").and(takesArguments(7)), getClass().getName() + "$MessageBodyWriterAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class MessageBodyWriterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(
        @Advice.Argument(0) Object entity,
        @Advice.Argument(4) MediaType mediaType,
        @ActiveRequestContext RequestContext reqCtx) {

      if (!MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType)) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.responseBody());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, entity);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction == null) {
          return;
        }
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        blockResponseFunction.tryCommitBlockingResponse(
            reqCtx.getTraceSegment(),
            rba.getStatusCode(),
            rba.getBlockingContentType(),
            rba.getExtraHeaders());

        throw new BlockingException("Blocked request (for MessageBodyWriter)");
      }
    }
  }
}
