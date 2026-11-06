package datadog.trace.instrumentation.tomcat9.executor;

import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context tracking for Tomcat's fork of ThreadPoolExecutor.
 *
 * <p>This module owns the Tomcat dependency and Muzzle checks while reusing bootstrap concurrency
 * helpers. Matching AbstractExecutorService broadly would include executors without Tomcat's queue
 * access and beforeExecute/afterExecute hooks.
 */
@AutoService(InstrumenterModule.class)
public final class TomcatExecutorModule extends InstrumenterModule.ContextTracking {
  public TomcatExecutorModule() {
    // Tomcat's executor uses the same context-propagation switch as JDK thread pools.
    super("java_concurrent", "tomcat_classloading");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("java.util.concurrent.RunnableFuture", State.class.getName());
    contextStore.put("java.lang.Runnable", State.class.getName());
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return singletonList(new TomcatThreadPoolExecutorInstrumentation());
  }
}
