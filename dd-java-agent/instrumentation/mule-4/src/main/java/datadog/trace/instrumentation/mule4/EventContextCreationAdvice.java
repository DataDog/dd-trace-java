package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.internal.event.DefaultEventContext;
import org.mule.runtime.tracer.api.EventTracer;

public class EventContextCreationAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static int onEnter() {
    return CallDepthThreadLocalMap.incrementCallDepth(EventContext.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This final EventContext zis,
      @Advice.Argument(0) final Object arg,
      @Advice.Enter final int depth) {
    // Only do work when exiting the topmost constructor
    if (depth > 0) {
      return;
    }
    CallDepthThreadLocalMap.reset(EventContext.class);

    final ContextStore<EventContext, SpanState> contextStore =
        InstrumentationContext.get(EventContext.class, SpanState.class);
    SpanState spanState = null;

    // This is a roundabout way to know if we are in the constructor for DefaultEventContext or
    // ChildContext. Since ChildContext is is a private inner class, we can't access it from here.
    if (zis instanceof DefaultEventContext) {
      spanState = new SpanState(activeSpan(), null);
    } else if (arg instanceof EventContext) {
      // This means that we are in the constructor for ChildContext and we should copy the span
      // from the parent EventContext which is the first argument.
      spanState = contextStore.get((EventContext) arg);
      if (spanState != null) {
        spanState = spanState.copy();
      } else {
        spanState = new SpanState(activeSpan(), null);
      }
    }
    contextStore.put(zis, spanState);
  }

  static void muzzleCheck(final EventTracer<?> tracer) {
    // introduced in 4.5.0
  }
}
