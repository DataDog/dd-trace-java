package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher.globalIgnoresMatcher;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableRedefinitionStrategyListener;
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers;
import datadog.trace.agent.tooling.bytebuddy.memoize.MemoizedMatchers;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.agent.tooling.usm.UsmExtractorImpl;
import datadog.trace.agent.tooling.usm.UsmMessageFactoryImpl;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Platform;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.telemetry.IntegrationsCollector;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.util.AgentTaskScheduler;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.LatentMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentInstaller {
  private static final Logger log = LoggerFactory.getLogger(AgentInstaller.class);
  private static final boolean DEBUG = log.isDebugEnabled();

  private static final List<Runnable> LOG_MANAGER_CALLBACKS = new CopyOnWriteArrayList<>();
  private static final List<Runnable> MBEAN_SERVER_BUILDER_CALLBACKS = new CopyOnWriteArrayList<>();

  static {
    addByteBuddyRawSetting();
    // register weak map supplier as early as possible
    WeakMaps.registerAsSupplier();
    circularityErrorWorkaround();
  }

  @SuppressForbidden
  private static void circularityErrorWorkaround() {
    // these classes have been involved in intermittent ClassCircularityErrors during startup
    // they don't need context storage, so it's safe to load them before installing the agent
    try {
      Class.forName("java.util.concurrent.ThreadLocalRandom");
    } catch (Throwable ignore) {
    }
  }

  public static void installBytebuddyAgent(final Instrumentation inst) {
    /*
     * ByteBuddy agent is used by several systems which can be enabled independently;
     * we need to install the agent whenever any of them is active.
     */
    Set<Instrumenter.TargetSystem> enabledSystems = getEnabledSystems();
    if (!enabledSystems.isEmpty()) {
      installBytebuddyAgent(inst, false, enabledSystems);
      if (DEBUG) {
        log.debug("Instrumentation installed for {}", enabledSystems);
      }
      int poolCleaningInterval = InstrumenterConfig.get().getResolverResetInterval();
      if (poolCleaningInterval > 0) {
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            SharedTypePools::clear,
            poolCleaningInterval,
            Math.max(poolCleaningInterval, 10),
            TimeUnit.SECONDS);
      }
    } else if (DEBUG) {
      log.debug("No target systems enabled, skipping instrumentation.");
    }
  }

  /**
   * Install the core bytebuddy agent along with all implementations of {@link Instrumenter}.
   *
   * @return the agent's class transformer
   */
  public static ClassFileTransformer installBytebuddyAgent(
      final Instrumentation inst,
      final boolean skipAdditionalLibraryMatcher,
      final Set<Instrumenter.TargetSystem> enabledSystems,
      final AgentBuilder.Listener... listeners) {
    Utils.setInstrumentation(inst);

    TypePoolFacade.registerAsSupplier();

    if (InstrumenterConfig.get().isResolverMemoizingEnabled()) {
      MemoizedMatchers.registerAsSupplier();
    } else {
      DDElementMatchers.registerAsSupplier();
    }

    if (enabledSystems.contains(Instrumenter.TargetSystem.USM)) {
      UsmMessageFactoryImpl.registerAsSupplier();
      UsmExtractorImpl.registerAsSupplier();
    }

    // By default ByteBuddy will skip all methods that are synthetic or default finalizer
    // but we need to instrument some synthetic methods in Scala, so change the ignore matcher
    ByteBuddy byteBuddy =
        new ByteBuddy().ignore(new LatentMatcher.Resolved<>(isDefaultFinalizer()));
    AgentBuilder agentBuilder =
        new AgentBuilder.Default(byteBuddy)
            .disableClassFormatChanges()
            .assureReadEdgeTo(inst, FieldBackedContextAccessor.class)
            .with(AgentStrategies.transformerDecorator())
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentStrategies.rediscoveryStrategy())
            .with(redefinitionStrategyListener(enabledSystems))
            .with(AgentStrategies.locationStrategy())
            .with(AgentStrategies.poolStrategy())
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(AgentStrategies.bufferStrategy())
            .with(AgentStrategies.typeStrategy())
            .with(new ClassLoadListener())
            // FIXME: we cannot enable it yet due to BB/JVM bug, see
            // https://github.com/raphw/byte-buddy/issues/558
            // .with(AgentBuilder.LambdaInstrumentationStrategy.ENABLED)
            .ignore(globalIgnoresMatcher(skipAdditionalLibraryMatcher));

    if (DEBUG) {
      agentBuilder =
          agentBuilder
              .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
              .with(AgentStrategies.rediscoveryStrategy())
              .with(redefinitionStrategyListener(enabledSystems))
              .with(new RedefinitionLoggingListener())
              .with(new TransformLoggingListener());
    }

    for (final AgentBuilder.Listener listener : listeners) {
      agentBuilder = agentBuilder.with(listener);
    }

    Instrumenters instrumenters = Instrumenters.load(AgentInstaller.class.getClassLoader());
    int maxInstrumentationId = instrumenters.maxInstrumentationId();

    // pre-size state before registering instrumentations to reduce number of allocations
    InstrumenterState.setMaxInstrumentationId(maxInstrumentationId);

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

    Instrumenter.TransformerBuilder transformerBuilder;
    if (InstrumenterConfig.get().isLegacyInstallerEnabled()) {
      transformerBuilder = new LegacyTransformerBuilder(agentBuilder);
    } else {
      transformerBuilder = new CombiningTransformerBuilder(agentBuilder, maxInstrumentationId);
    }

    int installedCount = 0;
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
        instrumenter.instrument(transformerBuilder);
        installedCount++;
      } catch (Exception | LinkageError e) {
        log.error(
            "Failed to load - instrumentation.class={}", instrumenter.getClass().getName(), e);
      }
    }
    if (DEBUG) {
      log.debug("Installed {} instrumenter(s)", installedCount);
    }

    if (!Platform.isNativeImageBuilder()) {
      InstrumenterFlare.register();
    }

    if (InstrumenterConfig.get().isTelemetryEnabled()) {
      InstrumenterState.setObserver(
          new InstrumenterState.Observer() {
            @Override
            public void applied(Iterable<String> instrumentationNames) {
              IntegrationsCollector.get().update(instrumentationNames, true);
            }
          });
    }

    InstrumenterState.resetDefaultState();
    try {
      return transformerBuilder.installOn(inst);
    } finally {
      SharedTypePools.endInstall();
    }
  }

  public static Set<Instrumenter.TargetSystem> getEnabledSystems() {
    EnumSet<Instrumenter.TargetSystem> enabledSystems =
        EnumSet.noneOf(Instrumenter.TargetSystem.class);
    InstrumenterConfig cfg = InstrumenterConfig.get();
    if (cfg.isTraceEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.TRACING);
    }
    if (cfg.isProfilingEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.PROFILING);
    }
    if (cfg.getAppSecActivation() != ProductActivation.FULLY_DISABLED) {
      enabledSystems.add(Instrumenter.TargetSystem.APPSEC);
    }
    if (cfg.getIastActivation() != ProductActivation.FULLY_DISABLED) {
      enabledSystems.add(Instrumenter.TargetSystem.IAST);
    }
    if (cfg.isCiVisibilityEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.CIVISIBILITY);
    }
    if (cfg.isUsmEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.USM);
    }
    return enabledSystems;
  }

  private static void addByteBuddyRawSetting() {
    final String savedPropertyValue = System.getProperty(TypeDefinition.RAW_TYPES_PROPERTY);
    try {
      System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, "true");
      final boolean rawTypes = TypeDescription.AbstractBase.RAW_TYPES;
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

  private static AgentBuilder.RedefinitionStrategy.Listener redefinitionStrategyListener(
      final Set<Instrumenter.TargetSystem> enabledSystems) {
    if (enabledSystems.contains(Instrumenter.TargetSystem.IAST)) {
      return TaintableRedefinitionStrategyListener.INSTANCE;
    } else {
      return AgentBuilder.RedefinitionStrategy.Listener.NoOp.INSTANCE;
    }
  }

  static class RedefinitionLoggingListener implements AgentBuilder.RedefinitionStrategy.Listener {

    private static final Logger log = LoggerFactory.getLogger(RedefinitionLoggingListener.class);

    @Override
    public void onBatch(final int index, final List<Class<?>> batch, final List<Class<?>> types) {}

    @Override
    public Iterable<? extends List<Class<?>>> onError(
        final int index,
        final List<Class<?>> batch,
        final Throwable throwable,
        final List<Class<?>> types) {
      if (DEBUG) {
        log.debug("Exception while retransforming {} classes: {}", batch.size(), batch, throwable);
      }
      return Collections.emptyList();
    }

    @Override
    public void onComplete(
        final int amount,
        final List<Class<?>> types,
        final Map<List<Class<?>>, Throwable> failures) {}
  }

  static class TransformLoggingListener implements AgentBuilder.Listener {

    private static final Logger log = LoggerFactory.getLogger(TransformLoggingListener.class);

    @Override
    public void onError(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final Throwable throwable) {
      if (DEBUG) {
        log.debug(
            "Transformation failed - instrumentation.target.class={} instrumentation.target.classloader={}",
            typeName,
            classLoader,
            throwable);
      }
    }

    @Override
    public void onTransformation(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final DynamicType dynamicType) {
      if (DEBUG) {
        log.debug(
            "Transformed - instrumentation.target.class={} instrumentation.target.classloader={}",
            typeDescription.getName(),
            classLoader);
      }
    }

    @Override
    public void onIgnored(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {
      //      log.debug("onIgnored {}", typeDescription.getName());
    }

    @Override
    public void onComplete(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {
      //      log.debug("onComplete {}", typeName);
    }

    @Override
    public void onDiscovery(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {
      //      log.debug("onDiscovery {}", typeName);
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
  public static void registerClassLoadCallback(final String className, final Runnable callback) {
    if ("java.util.logging.LogManager".equals(className)) {
      LOG_MANAGER_CALLBACKS.add(callback);
    } else if ("javax.management.MBeanServerBuilder".equals(className)) {
      MBEAN_SERVER_BUILDER_CALLBACKS.add(callback);
    } else if (DEBUG) {
      log.debug("Callback not registered for unexpected class {}", className);
    }
  }

  private static class ClassLoadListener implements AgentBuilder.Listener {
    @Override
    public void onDiscovery(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule javaModule,
        final boolean b) {}

    @Override
    public void onTransformation(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule javaModule,
        final boolean b,
        final DynamicType dynamicType) {}

    @Override
    public void onIgnored(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule javaModule,
        final boolean b) {}

    @Override
    public void onError(
        final String s,
        final ClassLoader classLoader,
        final JavaModule javaModule,
        final boolean b,
        final Throwable throwable) {}

    @Override
    public void onComplete(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule javaModule,
        final boolean b) {
      final List<Runnable> callbacks;
      // On Java 17 java.util.logging.LogManager is loaded early even though it's not initialized,
      // so wait for the LoggerContext to be initialized before the callbacks are processed.
      if ("java.util.logging.LogManager$LoggerContext".equals(typeName)) {
        callbacks = LOG_MANAGER_CALLBACKS;
      } else if ("javax.management.MBeanServerBuilder".equals(typeName)) {
        callbacks = MBEAN_SERVER_BUILDER_CALLBACKS;
      } else {
        callbacks = null;
      }
      if (callbacks != null) {
        for (final Runnable callback : callbacks) {
          callback.run();
        }
      }
    }
  }

  private AgentInstaller() {}
}
