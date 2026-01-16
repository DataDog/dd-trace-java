package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ExtensionFinder.findExtensions;
import static datadog.trace.agent.tooling.ExtensionLoader.loadExtensions;
import static datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher.globalIgnoresMatcher;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer;

import datadog.environment.SystemProperties;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.NexusAccessor;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
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
    enableByteBuddyRawTypes();
    disableByteBuddyNexus();
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
    Set<InstrumenterModule.TargetSystem> enabledSystems = getEnabledSystems();
    if (!enabledSystems.isEmpty()) {
      installBytebuddyAgent(inst, false, enabledSystems);
      if (DEBUG) {
        log.debug("Instrumentation installed for {}", enabledSystems);
      }
      int poolCleaningInterval = InstrumenterConfig.get().getResolverResetInterval();
      if (poolCleaningInterval > 0) {
        AgentTaskScheduler.get()
            .scheduleAtFixedRate(
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
   * Install the core bytebuddy agent along with all registered {@link InstrumenterModule}s.
   *
   * @return the agent's class transformer
   */
  public static ClassFileTransformer installBytebuddyAgent(
      final Instrumentation inst,
      final boolean skipAdditionalLibraryMatcher,
      final Set<InstrumenterModule.TargetSystem> enabledSystems,
      final AgentBuilder.Listener... listeners) {
    Utils.setInstrumentation(inst);

    TypePoolFacade.registerAsSupplier();

    if (InstrumenterConfig.get().isResolverMemoizingEnabled()) {
      MemoizedMatchers.registerAsSupplier();
    } else {
      DDElementMatchers.registerAsSupplier();
    }

    if (enabledSystems.contains(InstrumenterModule.TargetSystem.USM)) {
      UsmMessageFactoryImpl.registerAsSupplier();
      UsmExtractorImpl.registerAsSupplier();
    }

    // By default ByteBuddy will skip all methods that are synthetic or default finalizer
    // but we need to instrument some synthetic methods in Scala, so change the ignore matcher
    ByteBuddy byteBuddy =
        new ByteBuddy().ignore(new LatentMatcher.Resolved<>(isDefaultFinalizer()));

    boolean simpleMethodGraph = InstrumenterConfig.get().isResolverSimpleMethodGraph();
    if (simpleMethodGraph) {
      // faster compiler that just considers visibility of locally declared methods
      byteBuddy =
          byteBuddy
              .with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE)
              .with(VisibilityBridgeStrategy.Default.NEVER)
              .with(InstrumentedType.Factory.Default.FROZEN);
    }

    AgentBuilder agentBuilder = new AgentBuilder.Default(byteBuddy);
    if (simpleMethodGraph) {
      // faster strategy that assumes transformations use @Advice or AsmVisitorWrapper
      agentBuilder = agentBuilder.with(AgentBuilder.TypeStrategy.Default.DECORATE);
    }

    agentBuilder =
        agentBuilder
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

    InstrumenterIndex instrumenterIndex = InstrumenterIndex.readIndex();

    // pre-size state before registering instrumentations to reduce number of allocations
    InstrumenterState.initialize(instrumenterIndex.instrumentationCount());

    // combine known modules indexed at build-time with extensions contributed at run-time
    Iterable<InstrumenterModule> instrumenterModules = withExtensions(instrumenterIndex.modules());

    // This needs to be a separate loop through all instrumentations before we start adding
    // advice so that we can exclude field injection, since that will try to check exclusion
    // immediately and we don't have the ability to express dependencies between different
    // instrumentations to control the load order.
    for (InstrumenterModule module : instrumenterModules) {
      if (module instanceof ExcludeFilterProvider) {
        ExcludeFilterProvider provider = (ExcludeFilterProvider) module;
        ExcludeFilter.add(provider.excludedClasses());
        if (DEBUG) {
          log.debug(
              "Adding filtered classes - instrumentation.class={}", module.getClass().getName());
        }
      }
    }

    CombiningTransformerBuilder transformerBuilder =
        new CombiningTransformerBuilder(agentBuilder, instrumenterIndex);

    int installedCount = 0;
    for (InstrumenterModule module : instrumenterModules) {
      if (!module.isApplicable(enabledSystems)) {
        if (DEBUG) {
          log.debug("Not applicable - instrumentation.class={}", module.getClass().getName());
        }
        continue;
      }
      if (DEBUG) {
        log.debug("Loading - instrumentation.class={}", module.getClass().getName());
      }
      try {
        transformerBuilder.applyInstrumentation(module);
        installedCount++;
      } catch (Exception | LinkageError e) {
        log.error("Failed to load - instrumentation.class={}", module.getClass().getName(), e);
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

  /** Returns an iterable that combines the original sequence with any discovered extensions. */
  private static Iterable<InstrumenterModule> withExtensions(Iterable<InstrumenterModule> initial) {
    String extensionsPath = InstrumenterConfig.get().getTraceExtensionsPath();
    if (null != extensionsPath) {
      if (findExtensions(extensionsPath, InstrumenterModule.class)) {
        final List<InstrumenterModule> extensions = loadExtensions(InstrumenterModule.class);
        extensions.sort(Comparator.comparingInt(InstrumenterModule::order));
        return new Iterable<InstrumenterModule>() {
          @Override
          public Iterator<InstrumenterModule> iterator() {
            return withExtensions(initial.iterator(), extensions);
          }
        };
      }
    }
    return initial;
  }

  /** Returns an iterator that combines the original sequence with any discovered extensions. */
  private static Iterator<InstrumenterModule> withExtensions(
      final Iterator<InstrumenterModule> initial, final Iterable<InstrumenterModule> extensions) {
    return new Iterator<InstrumenterModule>() {
      private Iterator<InstrumenterModule> delegate = initial;

      @Override
      public boolean hasNext() {
        if (delegate.hasNext()) {
          return true;
        } else if (delegate == initial) {
          delegate = extensions.iterator();
          return delegate.hasNext();
        } else {
          return false;
        }
      }

      @Override
      public InstrumenterModule next() {
        if (hasNext()) {
          return delegate.next();
        } else {
          throw new NoSuchElementException();
        }
      }
    };
  }

  public static Set<InstrumenterModule.TargetSystem> getEnabledSystems() {
    EnumSet<InstrumenterModule.TargetSystem> enabledSystems =
        EnumSet.noneOf(InstrumenterModule.TargetSystem.class);
    InstrumenterConfig cfg = InstrumenterConfig.get();
    if (cfg.isTraceEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.TRACING);
    }
    if (cfg.isProfilingEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.PROFILING);
    }
    if (cfg.getAppSecActivation() != ProductActivation.FULLY_DISABLED) {
      enabledSystems.add(InstrumenterModule.TargetSystem.APPSEC);
    }
    if (cfg.getIastActivation() != ProductActivation.FULLY_DISABLED) {
      enabledSystems.add(InstrumenterModule.TargetSystem.IAST);
    }
    if (cfg.isCiVisibilityEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.CIVISIBILITY);
    }
    if (cfg.isUsmEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.USM);
    }
    if (cfg.isLlmObsEnabled()) {
      enabledSystems.add(InstrumenterModule.TargetSystem.LLMOBS);
    }
    return enabledSystems;
  }

  private static void enableByteBuddyRawTypes() {
    temporaryOverride("net.bytebuddy.raw", "true", AgentInstaller::rawTypesEnabled);
  }

  private static boolean rawTypesEnabled() {
    return TypeDescription.AbstractBase.RAW_TYPES; // must avoid touching this before the override
  }

  private static void disableByteBuddyNexus() {
    // disable byte-buddy's Nexus mechanism (we don't need it, and it triggers use of Unsafe)
    temporaryOverride("net.bytebuddy.nexus.disabled", "true", AgentInstaller::nexusDisabled);
  }

  private static boolean nexusDisabled() {
    return !NexusAccessor.isAlive(); // must avoid touching this before the override
  }

  /** Temporarily overrides a system property while checking it's had the intended side effect. */
  private static void temporaryOverride(String key, String value, BooleanSupplier sideEffect) {
    final String savedPropertyValue = SystemProperties.get(key);
    final boolean overridden = SystemProperties.set(key, value);
    if (!sideEffect.getAsBoolean() && DEBUG) {
      log.debug("Too late to apply {}={}", key, value);
    }
    if (overridden) {
      if (savedPropertyValue == null) {
        SystemProperties.clear(key);
      } else {
        SystemProperties.set(key, savedPropertyValue);
      }
    }
  }

  private static AgentBuilder.RedefinitionStrategy.Listener redefinitionStrategyListener(
      final Set<InstrumenterModule.TargetSystem> enabledSystems) {
    if (enabledSystems.contains(InstrumenterModule.TargetSystem.IAST)) {
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
