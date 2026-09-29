package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ScalaPromiseUnregisterModule extends InstrumenterModule.ContextTracking {

  public ScalaPromiseUnregisterModule() {
    super("scala_concurrent");
  }

  @Override
  public String muzzleDirective() {
    return "scala-promise-unregister";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "scala.concurrent.impl.Promise$Transformation", State.class.getName());
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("scala.concurrent.impl.Promise$DefaultPromise")
          .withMethod(
              new String[0],
              EXPECTS_NON_STATIC,
              "unregisterCallback",
              "V",
              "Lscala/concurrent/impl/Promise$Transformation;")
          .build()
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return singletonList(new DefaultPromiseCallbackInstrumentation());
  }
}
