package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_MAP_PER_STORE;

import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.ObjectStoreCleaner;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;

/** Installs the agent once per JVM and creates keys for the context-store benchmarks. */
final class ContextStoreBenchmarkSupport {
  private static final String MAP_PER_STORE_PROPERTY = "dd." + RUNTIME_CONTEXT_MAP_PER_STORE;

  /** Key types that must not get injected fields, across all the context-store benchmarks. */
  private static final String[] UNINJECTED_KEY_TYPES = {
    "context.benchmark.NonFieldInjectedKey",
    "context.benchmark.workload.UninjectedKey",
    "context.benchmark.workload.PartialKeyBase"
  };

  // static so the agent is only installed once per JVM, even when running with -f 0
  private static boolean installed;
  private static boolean installedMapPerStore;

  private ContextStoreBenchmarkSupport() {}

  /**
   * Installs the agent with the requested context-store mode, or the configured mode when {@code
   * mapPerStore} is {@code null}.
   *
   * @return whether the installed agent uses a map per store
   */
  static synchronized boolean installAgent(Boolean mapPerStore) throws Exception {
    if (!installed) {
      if (mapPerStore != null) {
        // must be set before AgentInstaller or ContextStores read the config
        System.setProperty(MAP_PER_STORE_PROPERTY, mapPerStore.toString());
      }

      // must load before installing the agent: retransformation can't add fields to a class
      // that's already loaded, so these key types will fall back to the store's map
      for (String type : UNINJECTED_KEY_TYPES) {
        Class.forName(type);
      }

      Instrumentation instrumentation = ByteBuddyAgent.install();
      AgentInstaller.installBytebuddyAgent(instrumentation);
      // scheduled by Agent.start in production; a no-op when map-per-store is enabled
      ObjectStoreCleaner.schedule();

      installedMapPerStore = InstrumenterConfig.get().isRuntimeContextMapPerStore();
      installed = true;
    }
    if (mapPerStore != null && mapPerStore != installedMapPerStore) {
      throw new IllegalStateException(
          "map-per-store="
              + installedMapPerStore
              + " is already installed, but "
              + mapPerStore
              + " was requested; run with forks");
    }
    return installedMapPerStore;
  }

  /** Creates a key of the given type, checking whether it has injected fields. */
  @SuppressWarnings("unchecked")
  static <K> K newKey(String typeName, boolean expectInjected) throws Exception {
    Object key = Class.forName(typeName).getConstructor().newInstance();
    boolean injected = false;
    for (Class<?> type : key.getClass().getInterfaces()) {
      injected |= type.getName().equals("datadog.instrument.fieldinject.KeyWithValue");
    }
    if (injected != expectInjected) {
      throw new IllegalStateException(
          typeName + " field injection: expected " + expectInjected + ", got " + injected);
    }
    return (K) key;
  }
}
