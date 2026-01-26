package datadog.trace.instrumentation.java.concurrent.timer;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.RUNNABLE_INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for java.util.Timer and TimerTask. */
@AutoService(InstrumenterModule.class)
public final class JavaTimerModule extends InstrumenterModule.Tracing {

  public JavaTimerModule() {
    super("java_timer", EXECUTOR_INSTRUMENTATION_NAME, RUNNABLE_INSTRUMENTATION_NAME);
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("java.lang.Runnable", State.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(new JavaTimerInstrumentation(), new TimerTaskInstrumentation());
  }
}
