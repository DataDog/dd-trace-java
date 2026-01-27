package datadog.trace.instrumentation.java.concurrent.timer;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.RUNNABLE_INSTRUMENTATION_NAME;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JavaTimerModule extends InstrumenterModule.ContextTracking {
  public JavaTimerModule() {
    super("java_timer", EXECUTOR_INSTRUMENTATION_NAME, RUNNABLE_INSTRUMENTATION_NAME);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.lang.Runnable", State.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new JavaTimerInstrumentation(), new TimerTaskInstrumentation());
  }
}
