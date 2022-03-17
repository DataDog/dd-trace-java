package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.skipClassLoader;
import static datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher.globalIgnoresMatcher;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isDefaultFinalizer;
import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.trace.agent.tooling.context.FieldBackedContextProvider;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
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
    // register weak map/cache suppliers as early as possible
    WeakMaps.registerAsSupplier();
    WeakCaches.registerAsSupplier();
  }

  public static void installBytebuddyAgent(final Instrumentation inst) {
    /*
     * ByteBuddy agent is used by tracing, profiling, appsec and civisibility and since they can
     * be enabled independently we need to install the agent when either of them
     * is active.
     */
    if (Config.get().isTraceEnabled()
        || Config.get().isProfilingEnabled()
        || Config.get().isAppSecEnabled()
        || Config.get().isCiVisibilityEnabled()) {
      installBytebuddyAgent(inst, false, new AgentBuilder.Listener[0]);
      if (DEBUG) {
        log.debug("Class instrumentation installed");
      }
    } else if (DEBUG) {
      log.debug("There are not any enabled subsystems, not installing instrumentations.");
    }
  }

  /**
   * Install the core bytebuddy agent along with all implementations of {@link Instrumenter}.
   *
   * @param inst Java Instrumentation used to install bytebuddy
   * @return the agent's class transformer
   */
  public static ResettableClassFileTransformer installBytebuddyAgent(
      final Instrumentation inst,
      final boolean skipAdditionalLibraryMatcher,
      final AgentBuilder.Listener... listeners) {
    Utils.setInstrumentation(inst);

    FieldBackedContextProvider.resetContextMatchers();

    // By default ByteBuddy will skip all methods that are synthetic or default finalizer
    // but we need to instrument some synthetic methods in Scala, so change the ignore matcher
    ByteBuddy byteBuddy =
        new ByteBuddy().ignore(new LatentMatcher.Resolved<>(isDefaultFinalizer()));
    AgentBuilder.Ignored ignoredAgentBuilder =
        new AgentBuilder.Default(byteBuddy)
            .disableClassFormatChanges()
            .assureReadEdgeTo(inst, FieldBackedContextAccessor.class)
            .with(AgentStrategies.transformerDecorator())
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentStrategies.rediscoveryStrategy())
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(AgentStrategies.poolStrategy())
            .with(new ClassLoadListener())
            .with(AgentStrategies.locationStrategy())
            // FIXME: we cannot enable it yet due to BB/JVM bug, see
            // https://github.com/raphw/byte-buddy/issues/558
            // .with(AgentBuilder.LambdaInstrumentationStrategy.ENABLED)
            .ignore(any(), skipClassLoader());

    ignoredAgentBuilder =
        ignoredAgentBuilder.or(globalIgnoresMatcher(skipAdditionalLibraryMatcher));

    ignoredAgentBuilder = ignoredAgentBuilder.or(matchesConfiguredExcludes());

    AgentBuilder agentBuilder = ignoredAgentBuilder;
    if (DEBUG) {
      agentBuilder =
          agentBuilder
              .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
              .with(AgentStrategies.rediscoveryStrategy())
              .with(new RedefinitionLoggingListener())
              .with(new TransformLoggingListener());
    }

    for (final AgentBuilder.Listener listener : listeners) {
      agentBuilder = agentBuilder.with(listener);
    }
    int numInstrumenters = 0;
    ServiceLoader<Instrumenter> loader =
        ServiceLoader.load(Instrumenter.class, AgentInstaller.class.getClassLoader());
    // This needs to be a separate loop through all the instrumenters before we start adding
    // transfomers so that we can exclude field injection, since that will try to check exclusion
    // immediately and we don't have the ability to express dependencies between different
    // instrumenters to control the load order.
    for (final Instrumenter instrumenter : loader) {
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

    AgentTransformerBuilder transformerBuilder = new AgentTransformerBuilder(agentBuilder);

    Set<Instrumenter.TargetSystem> enabledSystems = getEnabledSystems();
    for (final Instrumenter instrumenter : loader) {
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
        numInstrumenters++;
      } catch (final Exception | LinkageError e) {
        log.error(
            "Failed to load - instrumentation.class={}", instrumenter.getClass().getName(), e);
      }
    }
    if (DEBUG) {
      log.debug("Installed {} instrumenter(s)", numInstrumenters);
    }

    return transformerBuilder.installOn(inst);
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
    if (cfg.isAppSecEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.APPSEC);
    }
    if (cfg.isCiVisibilityEnabled()) {
      enabledSystems.add(Instrumenter.TargetSystem.CIVISIBILITY);
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

  private static ElementMatcher.Junction<Object> matchesConfiguredExcludes() {
    final List<String> excludedClasses = Config.get().getExcludedClasses();
    ElementMatcher.Junction matcher = none();
    if (!excludedClasses.isEmpty()) {
      List<String> literals = new ArrayList<>();
      List<String> prefixes = new ArrayList<>();
      // first accumulate by operation because a lot of work can be aggregated
      for (String excludedClass : excludedClasses) {
        excludedClass = excludedClass.trim();
        if (excludedClass.endsWith("*")) {
          // remove the trailing *
          prefixes.add(excludedClass.substring(0, excludedClass.length() - 1));
        } else {
          literals.add(excludedClass);
        }
      }
      if (!literals.isEmpty()) {
        matcher = matcher.or(namedOneOf(literals));
      }
      for (String prefix : prefixes) {
        // TODO - with a prefix tree this matching logic can be handled by a
        // single longest common prefix query
        matcher = matcher.or(nameStartsWith(prefix));
      }
    }
    return matcher;
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
      log.debug("Callback not registered for unexpecte class {}", className);
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
      if ("java.util.logging.LogManager".equals(typeName)) {
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
