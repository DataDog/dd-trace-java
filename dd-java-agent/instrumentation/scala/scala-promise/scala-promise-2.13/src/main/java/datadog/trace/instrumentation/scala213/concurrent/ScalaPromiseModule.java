package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
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
    return "scala-promise-2.13";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("scala.util.Try", Context.class.getName());
    contextStore.put("scala.concurrent.impl.Promise$Transformation", State.class.getName());
    return contextStore;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.scala.PromiseHelper"};
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // force other instrumentations (e.g. Runnable) not to deal with this type
    Map<ExcludeFilter.ExcludeType, Collection<String>> map = new HashMap<>();
    Collection<String> pt = Collections.singleton("scala.concurrent.impl.Promise$Transformation");
    map.put(RUNNABLE, pt);
    map.put(EXECUTOR, pt);
    return map;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new DefaultPromiseInstrumentation(),
        new FutureObjectInstrumentation(),
        new PromiseObjectInstrumentation(),
        new PromiseTransformationInstrumentation());
  }
}
