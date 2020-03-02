package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDDescriptionStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDLocationStrategy;
import datadog.trace.agent.tooling.bytebuddy.caching.WeakRefClassLoaderCache;
import datadog.trace.bootstrap.WeakMap;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * This class contains class references for objects shared by the agent installer as well as muzzle
 * (both compile and runtime). Extracted out from AgentInstaller to begin separating some of the
 * logic out.
 */
public class AgentTooling {

  static {
    // WeakMap is used by other classes below, so we need to register the provider first.
    registerWeakMapProvider();
  }

  private static final WeakRefClassLoaderCache LOADER_REF_CACHE = new WeakRefClassLoaderCache();
  private static final DDDescriptionStrategy DESCRIPTION_STRATEGY =
      new DDDescriptionStrategy(LOADER_REF_CACHE);
  private static final DDCachingPoolStrategy POOL_STRATEGY =
      new DDCachingPoolStrategy(LOADER_REF_CACHE);
  private static final DDLocationStrategy LOCATION_STRATEGY = new DDLocationStrategy();

  public static DDCachingPoolStrategy poolStrategy() {
    return POOL_STRATEGY;
  }

  public static DDLocationStrategy locationStrategy() {
    return LOCATION_STRATEGY;
  }

  private static void registerWeakMapProvider() {
    if (!WeakMap.Provider.isProviderRegistered()) {
      WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.WeakConcurrent(new Cleaner()));
      //    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.WeakConcurrent.Inline());
      //    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.Guava());
    }
  }

  public static AgentBuilder.DescriptionStrategy descriptionStrategy() {
    return DESCRIPTION_STRATEGY;
  }
}
