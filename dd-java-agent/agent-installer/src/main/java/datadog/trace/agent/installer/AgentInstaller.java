package datadog.trace.agent.installer;

import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Instrumenters;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.WeakCaches;
import datadog.trace.agent.tooling.WeakMaps;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.api.ProductActivationConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentInstaller {
  private static final Logger log = LoggerFactory.getLogger(AgentInstaller.class);
  private static final boolean DEBUG = log.isDebugEnabled();

  private static final List<Runnable> LOG_MANAGER_CALLBACKS = new CopyOnWriteArrayList<>();
  private static final List<Runnable> MBEAN_SERVER_BUILDER_CALLBACKS = new CopyOnWriteArrayList<>();

  static {
    addByteBuddyRawSetting();
    // register weak map/cache suppliers as early as possible
    WeakMaps.registerAsSupplier();
    WeakCaches.registerAsSupplier();
  }

  public static void installBytebuddyAgent(Instrumentation inst) {
    if (Config.get().isTraceEnabled()
        || Config.get().isProfilingEnabled()
        || Config.get().getAppSecEnabledConfig() != ProductActivationConfig.FULLY_DISABLED
        || Config.get().isCiVisibilityEnabled()) {
      Utils.setInstrumentation(inst);
      installClassTransformer(inst);
      installInstrumenters();
      if (DEBUG) {
        log.debug("Datadog class transformer installed.");
      }
    } else if (DEBUG) {
      log.debug("No instrumentation required, not installing Datadog class transformer.");
    }
  }

  /**
   * Register a callback to run when a class is loading.
   *
   * <p>Caveats:
   *
   * <ul>
   *   <li>This callback will be invoked by a jvm class transformer.
   *   <li>Classes filtered out by {@link AgentInstaller}'s skip list will not be matched.
   * </ul>
   *
   * @param className name of the class to match against
   * @param callback runnable to invoke when class name matches
   */
  public static void registerClassLoadCallback(String className, Runnable callback) {
    if ("java.util.logging.LogManager".equals(className)) {
      LOG_MANAGER_CALLBACKS.add(callback);
    } else if ("javax.management.MBeanServerBuilder".equals(className)) {
      MBEAN_SERVER_BUILDER_CALLBACKS.add(callback);
    } else if (DEBUG) {
      log.debug("Callback not registered for unexpected class {}", className);
    }
  }

  private static void addByteBuddyRawSetting() {
    String savedPropertyValue = System.getProperty(TypeDefinition.RAW_TYPES_PROPERTY);
    try {
      System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, "true");
      boolean rawTypes = TypeDescription.AbstractBase.RAW_TYPES;
      if (!rawTypes && DEBUG) {
        log.debug("Too late to enable {}", TypeDefinition.RAW_TYPES_PROPERTY);
      }
    } finally {
      if (savedPropertyValue == null) {
        System.clearProperty(TypeDefinition.RAW_TYPES_PROPERTY);
      } else {
        System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, savedPropertyValue);
      }
    }
  }

  private static void installClassTransformer(Instrumentation inst) {
    ClassFileTransformer transformer = null;
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        transformer =
            (ClassFileTransformer)
                Instrumenter.class
                    .getClassLoader()
                    .loadClass("datadog.trace.agent.installer.DDJava9ClassFileTransformer")
                    .getDeclaredConstructor()
                    .newInstance();
      } catch (Throwable e) {
        log.warn(
            "Problem loading Java9 Module support, falling back to non-modular class transformer",
            e);
      }
    }
    if (null == transformer) {
      transformer = new DDClassFileTransformer();
    }
    inst.addTransformer(transformer, true);
  }

  private static void installInstrumenters() {
    Iterable<Instrumenter> instrumenters =
        Instrumenters.load(AgentInstaller.class.getClassLoader());

    // This needs to be a separate loop through all the instrumenters before we start adding
    // advice so that we can exclude field injection, since that will try to check exclusion
    // immediately and we don't have the ability to express dependencies between different
    // instrumenters to control the load order.
    for (Instrumenter instrumenter : instrumenters) {
      if (instrumenter instanceof ExcludeFilterProvider) {
        ExcludeFilterProvider provider = (ExcludeFilterProvider) instrumenter;
        ExcludeFilter.add(provider.excludedClasses());
        if (DEBUG) {
          log.debug(
              "Adding filtered classes - instrumentation.class={}",
              instrumenter.getClass().getName());
        }
      }
    }

    int installedCount = 0;
    Set<Instrumenter.TargetSystem> enabledSystems = getEnabledSystems();
    for (Instrumenter instrumenter : instrumenters) {
      if (!instrumenter.isApplicable(enabledSystems)) {
        if (DEBUG) {
          log.debug("Not applicable - instrumentation.class={}", instrumenter.getClass().getName());
        }
        continue;
      }
      if (DEBUG) {
        log.debug("Loading - instrumentation.class={}", instrumenter.getClass().getName());
      }
      try {
        instrumenter.instrument(
            new Instrumenter.TransformerBuilder() {
              @Override
              public void applyInstrumentation(Instrumenter.HasAdvice instrumenter) {
                // apply instrumentation...
              }
            });
        installedCount++;
      } catch (Exception | LinkageError e) {
        log.error(
            "Failed to load - instrumentation.class={}", instrumenter.getClass().getName(), e);
      }
    }
    if (DEBUG) {
      log.debug("Installed {} instrumenter(s)", installedCount);
    }
  }

  private static Set<Instrumenter.TargetSystem> getEnabledSystems() {
    EnumSet<Instrumenter.TargetSystem> enabledSystems =
        EnumSet.noneOf(Instrumenter.TargetSystem.class);
    Config cfg = Config.get();
    if (cfg.isTraceEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.TRACING);
    }
    if (cfg.isProfilingEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.PROFILING);
    }
    if (cfg.getAppSecEnabledConfig() != ProductActivationConfig.FULLY_DISABLED) {
      enabledSystems.add(Instrumenter.TargetSystem.APPSEC);
    }
    if (cfg.isCiVisibilityEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.CIVISIBILITY);
    }
    return enabledSystems;
  }

  private AgentInstaller() {}
}
