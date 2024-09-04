package datadog.trace.instrumentation.mule4;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.api.profiling.tracing.Span;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;

@AutoService(InstrumenterModule.class)
public class EventTracerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public EventTracerInstrumentation() {
    super("mule-otel");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.mule.runtime.api.event.EventContext", Pair.class.getName());
    ret.put(
        "org.mule.runtime.api.profiling.tracing.Span",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
    return ret;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CurrentEventHelper", packageName + ".DDEventTracer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        NameMatchers.named("updateSelectedCoreEventTracer"),
        getClass().getName() + "$SwapCoreTracerAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.tracer.impl.SelectableCoreEventTracer";
  }

  public static class SwapCoreTracerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void afterInit(
        @Advice.FieldValue(value = "selectedCoreEventTracer", readOnly = false)
            EventTracer<CoreEvent> eventTracer) {
      System.err.println("SWAPPING " + eventTracer);
      eventTracer =
          new DDEventTracer(
              InstrumentationContext.get(Span.class, AgentSpan.class),
              InstrumentationContext.get(EventContext.class, Pair.class),
              eventTracer);
    }
  }
}
