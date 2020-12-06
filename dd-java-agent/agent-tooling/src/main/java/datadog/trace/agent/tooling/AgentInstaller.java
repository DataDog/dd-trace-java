package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.skipClassLoader;
import static datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher.globalIgnoresMatcher;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.trace.agent.tooling.context.FieldBackedProvider;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

@Slf4j
public class AgentInstaller {
  private static final ConcurrentHashMap<String, List<Runnable>> CLASS_LOAD_CALLBACKS =
      new ConcurrentHashMap<>();
  private static volatile Instrumentation INSTRUMENTATION;

  public static Instrumentation getInstrumentation() {
    return INSTRUMENTATION;
  }

  static {
    // WeakMap is used by other classes below, so we need to register the provider first.
    AgentTooling.registerWeakMapProvider();
  }

  public static void installBytebuddyAgent(final Instrumentation inst) {
    /*
     * ByteBuddy agent is used by both tracing and profiling and since they can
     * be enabled independently we need to install the agent when either of them
     * is active.
     */
    if (Config.get().isTraceEnabled() || Config.get().isProfilingEnabled()) {
      installBytebuddyAgent(inst, false, new AgentBuilder.Listener[0]);
      log.debug("Class instrumentation installed");
    } else {
      log.debug("Tracing is disabled, not installing instrumentations.");
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
    INSTRUMENTATION = inst;

    addByteBuddyRawSetting();

    if (Config.get().isLegacyContextFieldInjection()) {
      FieldBackedProvider.resetContextMatchers();
    } else {
      // reset new field-injection strategy
    }

    AgentBuilder.Ignored ignoredAgentBuilder =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(AgentTooling.poolStrategy())
            .with(new ClassLoadListener())
            .with(AgentTooling.locationStrategy())
            // FIXME: we cannot enable it yet due to BB/JVM bug, see
            // https://github.com/raphw/byte-buddy/issues/558
            // .with(AgentBuilder.LambdaInstrumentationStrategy.ENABLED)
            .ignore(any(), skipClassLoader());

    ignoredAgentBuilder =
        ignoredAgentBuilder.or(globalIgnoresMatcher(skipAdditionalLibraryMatcher));

    ignoredAgentBuilder = ignoredAgentBuilder.or(matchesConfiguredExcludes());

    AgentBuilder agentBuilder = ignoredAgentBuilder;
    if (log.isDebugEnabled()) {
      agentBuilder =
          agentBuilder
              .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
              .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
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
        log.debug(
            "Adding filtered classes from instrumentation {}", instrumenter.getClass().getName());
      }
    }

    Set<Instrumenter.TargetSystem> enabledSystems = getEnabledSystems();
    for (final Instrumenter instrumenter : loader) {
      if (!instrumenter.isApplicable(enabledSystems)) {
        log.debug("Instrumentation {} is not applicable", instrumenter.getClass().getName());
        continue;
      }
      log.debug("Loading instrumentation {}", instrumenter.getClass().getName());

      try {
        agentBuilder = instrumenter.instrument(agentBuilder);
        numInstrumenters++;
      } catch (final Exception | LinkageError e) {
        log.error("Unable to load instrumentation {}", instrumenter.getClass().getName(), e);
      }
    }
    log.debug("Installed {} instrumenter(s)", numInstrumenters);

    return agentBuilder.installOn(inst);
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
    return enabledSystems;
  }

  private static void addByteBuddyRawSetting() {
    final String savedPropertyValue = System.getProperty(TypeDefinition.RAW_TYPES_PROPERTY);
    try {
      System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, "true");
      final boolean rawTypes = TypeDescription.AbstractBase.RAW_TYPES;
      if (!rawTypes) {
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
    return matcher;
  }

  @Slf4j
  static class RedefinitionLoggingListener implements AgentBuilder.RedefinitionStrategy.Listener {

    @Override
    public void onBatch(final int index, final List<Class<?>> batch, final List<Class<?>> types) {}

    @Override
    public Iterable<? extends List<Class<?>>> onError(
        final int index,
        final List<Class<?>> batch,
        final Throwable throwable,
        final List<Class<?>> types) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Exception while retransforming " + batch.size() + " classes: " + batch, throwable);
      }
      return Collections.emptyList();
    }

    @Override
    public void onComplete(
        final int amount,
        final List<Class<?>> types,
        final Map<List<Class<?>>, Throwable> failures) {}
  }

  @Slf4j
  static class TransformLoggingListener implements AgentBuilder.Listener {

    @Override
    public void onError(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final Throwable throwable) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Failed to handle {} for transformation on classloader {}: {}",
            typeName,
            classLoader,
            throwable.getMessage());
      }
    }

    @Override
    public void onTransformation(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final DynamicType dynamicType) {
      log.debug("Transformed {} -- {}", typeDescription.getName(), classLoader);
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
    CLASS_LOAD_CALLBACKS.putIfAbsent(className, new CopyOnWriteArrayList<Runnable>());
    CLASS_LOAD_CALLBACKS.get(className).add(callback);
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
      final List<Runnable> callbacks = CLASS_LOAD_CALLBACKS.get(typeName);
      if (callbacks != null) {
        for (final Runnable callback : callbacks) {
          callback.run();
        }
      }
    }
  }

  private AgentInstaller() {}
}
