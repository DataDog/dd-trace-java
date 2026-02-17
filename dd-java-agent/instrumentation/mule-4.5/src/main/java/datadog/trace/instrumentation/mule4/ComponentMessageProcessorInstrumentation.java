package datadog.trace.instrumentation.mule4;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;

/**
 * Tries to activate the current event context span before dispatching the event to the current
 * handler.
 */
@AutoService(InstrumenterModule.class)
public class ComponentMessageProcessorInstrumentation extends AbstractMuleInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.module.extension.internal.runtime.operation.ComponentMessageProcessor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("onEvent", "onEventSynchronous", "prepareAndExecuteOperation"))
            .and(takesArgument(0, named("org.mule.runtime.core.api.event.CoreEvent"))),
        getClass().getName() + "$ProcessAdvice");
  }

  @AppliesOn(CONTEXT_TRACKING)
  public static class ProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.Argument(0) final CoreEvent event) {
      if (event == null || event.getContext() == null) {
        return null;
      }
      SpanState spanState =
          InstrumentationContext.get(EventContext.class, SpanState.class).get(event.getContext());
      if (spanState != null && spanState.getEventContextSpan() != null) {
        return activateSpan(spanState.getSpanContextSpan());
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }

    private static void muzzleCheck(final EventTracer<?> tracer) {
      // introduced in 4.5.0
      tracer.endCurrentSpan(null);
    }
  }
}
