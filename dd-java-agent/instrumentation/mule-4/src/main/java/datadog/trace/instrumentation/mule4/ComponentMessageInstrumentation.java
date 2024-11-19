package datadog.trace.instrumentation.mule4;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;

/**
 * Tries to activate the current event context span before dispatching the event to the current
 * handler.
 */
@AutoService(InstrumenterModule.class)
public class ComponentMessageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public ComponentMessageInstrumentation() {
    super("mule");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.mule.runtime.api.event.EventContext", packageName + ".SpanState");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpanState",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        ElementMatchers.isMethod()
            .and(NameMatchers.namedOneOf("onEvent", "onEventSynchronous"))
            .and(
                ElementMatchers.takesArgument(
                    0, NameMatchers.named("org.mule.runtime.core.api.event.CoreEvent"))),
        getClass().getName() + "$ProcessAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.module.extension.internal.runtime.operation.ComponentMessageProcessor";
  }

  public static class ProcessAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.Argument(0) final CoreEvent event) {
      if (event == null || event.getContext() == null) {
        return null;
      }
      SpanState spanState =
          InstrumentationContext.get(EventContext.class, SpanState.class).get(event.getContext());
      if (spanState != null && spanState.getEventContextSpan() != null) {
        return AgentTracer.activateSpan(spanState.getSpanContextSpan());
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
