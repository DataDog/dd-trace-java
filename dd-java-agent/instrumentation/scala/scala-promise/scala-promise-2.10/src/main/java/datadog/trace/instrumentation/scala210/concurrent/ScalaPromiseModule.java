package datadog.trace.instrumentation.scala210.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ScalaPromiseModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {

  public ScalaPromiseModule() {
    super("scala_concurrent");
  }

  @Override
  public String muzzleDirective() {
    return "scala-promise-2.10";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("scala.util.Try", Context.class.getName());
    contextStore.put("scala.concurrent.impl.CallbackRunnable", State.class.getName());
    return contextStore;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // force other instrumentations (e.g. Runnable) not to deal with this type
    Map<ExcludeFilter.ExcludeType, Collection<String>> map = new HashMap<>();
    Collection<String> cbr = singleton("scala.concurrent.impl.CallbackRunnable");
    map.put(RUNNABLE, cbr);
    map.put(EXECUTOR, cbr);
    return map;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.scala.PromiseHelper"};
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>(3);
    instrumenters.add(new CallbackRunnableInstrumentation());
    instrumenters.add(new FutureObjectInstrumentation());
    // Only enable this if integrations have been enabled and the extra "integration"
    // scala_promise_completion_priority has been enabled specifically
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(
            Collections.singletonList("scala_promise_completion_priority"), false)) {
      instrumenters.add(new PromiseObjectInstrumentation());
    }
    return instrumenters;
  }
}
