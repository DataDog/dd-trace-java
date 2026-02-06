package datadog.trace.instrumentation.scala213.concurrent;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ScalaPromiseModule extends InstrumenterModule.ContextTracking {

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
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new DefaultPromiseInstrumentation(),
        new FutureObjectInstrumentation(),
        new PromiseObjectInstrumentation(),
        new PromiseTransformationInstrumentation());
  }
}
