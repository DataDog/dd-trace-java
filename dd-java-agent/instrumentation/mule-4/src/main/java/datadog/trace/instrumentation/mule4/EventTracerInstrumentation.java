package datadog.trace.instrumentation.mule4;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;

@AutoService(InstrumenterModule.class)
public class EventTracerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public EventTracerInstrumentation() {
    super("mule");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> contextStore = new HashMap<>();
    contextStore.put("org.mule.runtime.api.event.EventContext", packageName + ".SpanState");
    contextStore.put(
        "org.mule.runtime.tracer.api.span.info.InitialSpanInfo",
        "org.mule.runtime.api.component.Component");
    return contextStore;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MuleDecorator",
      packageName + ".DDEventTracer",
      packageName + ".SpanState",
      packageName + ".NoopMuleSpan",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.tracer.impl.SelectableCoreEventTracer";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        NameMatchers.named("updateSelectedCoreEventTracer"),
        getClass().getName() + "$SwapCoreTracerAdvice");
  }

  public static class SwapCoreTracerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterInit(
        @Advice.FieldValue(value = "selectedCoreEventTracer", readOnly = false)
            EventTracer<CoreEvent> eventTracer) {
      eventTracer =
          new DDEventTracer(
              InstrumentationContext.get(EventContext.class, SpanState.class),
              InstrumentationContext.get(InitialSpanInfo.class, Component.class),
              eventTracer);
    }
  }
}
