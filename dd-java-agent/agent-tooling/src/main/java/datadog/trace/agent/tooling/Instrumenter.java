package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.failSafe;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.bytebuddy.DDTransformers;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.context.FieldBackedProvider;
import datadog.trace.agent.tooling.context.InstrumentationContextProvider;
import datadog.trace.agent.tooling.context.NoopContextProvider;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.api.Config;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;
import net.bytebuddy.utility.JavaModule;

/**
 * Built-in bytebuddy-based instrumentation for the datadog javaagent.
 *
 * <p>It is strongly recommended to extend {@link Default} rather than implement this interface
 * directly.
 */
public interface Instrumenter {
  /**
   * Since several subsystems are sharing the same instrumentation infractructure in order to enable
   * only the applicable {@link Instrumenter instrumenters} on startup each {@linkplain
   * Instrumenter} type must declare its target system. Currently only two systems are supported
   *
   * <ul>
   *   <li>{@link TargetSystem#TRACING tracing}
   *   <li>{@link TargetSystem#PROFILING profiling}
   * </ul>
   */
  enum TargetSystem {
    TRACING,
    PROFILING
  }

  /**
   * Add this instrumentation to an AgentBuilder.
   *
   * @param agentBuilder AgentBuilder to base instrumentation config off of.
   * @return the original agentBuilder and this instrumentation
   */
  AgentBuilder instrument(AgentBuilder agentBuilder);

  /**
   * Indicates the applicability of an {@linkplain Instrumenter} to the given system.<br>
   *
   * @param enabledSystems a set of all the enabled target systems
   * @return {@literal true} if the set of enabled systems contains all the ones required by this
   *     particular {@linkplain Instrumenter}
   */
  boolean isApplicable(Set<TargetSystem> enabledSystems);

  @Slf4j
  abstract class Default implements Instrumenter {
    private static final ElementMatcher<ClassLoader> ANY_CLASS_LOADER = any();

    // Added here instead of AgentInstaller's ignores because it's relatively
    // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
    public static final Junction<AnnotationSource> NOT_DECORATOR_MATCHER =
        not(isAnnotatedWith(named("javax.decorator.Decorator")));

    private final SortedSet<String> instrumentationNames;
    private final String instrumentationPrimaryName;
    private InstrumentationContextProvider contextProvider;
    private boolean initialized;
    private final boolean enabled;

    protected final String packageName =
        getClass().getPackage() == null ? "" : getClass().getPackage().getName();

    public Default(final String instrumentationName, final String... additionalNames) {
      instrumentationNames = new TreeSet<>(Arrays.asList(additionalNames));
      instrumentationNames.add(instrumentationName);
      instrumentationPrimaryName = instrumentationName;

      enabled = Config.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
    }

    // Since the super(...) call is first in the constructor, we can't really rely on things
    // being properly initialized in the Instrumentation until the super(...) call has finished
    // so do the rest of the initialization lazily
    private void lazyInit() {
      synchronized (this) {
        if (!initialized) {
          Map<ElementMatcher<ClassLoader>, Map<String, String>> contextStores;
          Map<String, String> allClassLoaderContextStores = contextStoreForAll();
          Map<String, String> matchedContextStores = contextStore();
          if (allClassLoaderContextStores.isEmpty()) {
            if (matchedContextStores.isEmpty()) {
              contextStores = emptyMap();
            } else {
              contextStores = singletonMap(classLoaderMatcher(), matchedContextStores);
            }
          } else {
            if (contextStore().isEmpty()) {
              contextStores = singletonMap(ANY_CLASS_LOADER, allClassLoaderContextStores);
            } else {
              contextStores = new HashMap<>();
              contextStores.put(classLoaderMatcher(), matchedContextStores);
              contextStores.put(ANY_CLASS_LOADER, allClassLoaderContextStores);
            }
          }
          if (!contextStores.isEmpty()) {
            if (Config.get().isLegacyContextFieldInjection()) {
              contextProvider = new FieldBackedProvider(this, contextStores);
            } else {
              // call new field-injection strategy
            }
          } else {
            contextProvider = NoopContextProvider.INSTANCE;
          }
          initialized = true;
        }
      }
    }

    @Override
    public final AgentBuilder instrument(final AgentBuilder parentAgentBuilder) {
      if (!enabled) {
        log.debug("Instrumentation {} is disabled", this);
        return parentAgentBuilder;
      }

      lazyInit();

      AgentBuilder.Identified.Extendable agentBuilder =
          parentAgentBuilder
              .type(
                  failSafe(
                      typeMatcher(),
                      "Instrumentation type matcher unexpected exception: " + getClass().getName()),
                  failSafe(
                      classLoaderMatcher(),
                      "Instrumentation class loader matcher unexpected exception: "
                          + getClass().getName()))
              .and(NOT_DECORATOR_MATCHER)
              .and(new MuzzleMatcher())
              .and(new PostMatchHook())
              .transform(DDTransformers.defaultTransformers());
      agentBuilder = injectHelperClasses(agentBuilder);
      agentBuilder = contextProvider.instrumentationTransformer(agentBuilder);
      agentBuilder = applyInstrumentationTransformers(agentBuilder);
      agentBuilder = contextProvider.additionalInstrumentation(agentBuilder);
      return agentBuilder;
    }

    private AgentBuilder.Identified.Extendable injectHelperClasses(
        AgentBuilder.Identified.Extendable agentBuilder) {
      final String[] helperClassNames = helperClassNames();
      if (helperClassNames.length > 0) {
        agentBuilder =
            agentBuilder.transform(
                new HelperInjector(getClass().getSimpleName(), helperClassNames));
      }
      return agentBuilder;
    }

    private AgentBuilder.Identified.Extendable applyInstrumentationTransformers(
        AgentBuilder.Identified.Extendable agentBuilder) {
      for (final Map.Entry<? extends ElementMatcher, String> entry : transformers().entrySet()) {
        agentBuilder =
            agentBuilder.transform(
                new AgentBuilder.Transformer.ForAdvice()
                    .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                    .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                    .advice(entry.getKey(), entry.getValue()));
      }
      return agentBuilder;
    }

    /** Matches classes for which instrumentation is not muzzled. */
    private class MuzzleMatcher implements AgentBuilder.RawMatcher {
      @Override
      public boolean matches(
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module,
          final Class<?> classBeingRedefined,
          final ProtectionDomain protectionDomain) {
        /* Optimization: calling getInstrumentationMuzzle() inside this method
         * prevents unnecessary loading of muzzle references during agentBuilder
         * setup.
         */
        final ReferenceMatcher muzzle = getInstrumentationMuzzle();
        if (null != muzzle) {
          final boolean isMatch = muzzle.matches(classLoader);
          if (!isMatch) {
            if (log.isDebugEnabled()) {
              final List<Reference.Mismatch> mismatches =
                  muzzle.getMismatchedReferenceSources(classLoader);
              if (log.isDebugEnabled()) {
                log.debug(
                    "Instrumentation muzzled: {} -- {} on {}",
                    instrumentationNames,
                    Instrumenter.Default.this.getClass().getName(),
                    classLoader);
              }
              for (final Reference.Mismatch mismatch : mismatches) {
                log.debug("-- {}", mismatch);
              }
            }
          } else {
            if (log.isDebugEnabled()) {
              log.debug(
                  "Applying instrumentation: {} -- {} on {}",
                  instrumentationPrimaryName,
                  Instrumenter.Default.this.getClass().getName(),
                  classLoader);
            }
          }
          return isMatch;
        }
        return true;
      }
    }

    private class PostMatchHook implements AgentBuilder.RawMatcher {
      @Override
      public boolean matches(
          final TypeDescription typeDescription,
          final ClassLoader classLoader,
          final JavaModule module,
          final Class<?> classBeingRedefined,
          final ProtectionDomain protectionDomain) {
        postMatch(typeDescription, classLoader, module, classBeingRedefined, protectionDomain);
        return true;
      }
    }

    /**
     * This method is implemented dynamically by compile-time bytecode transformations.
     *
     * <p>{@see datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin}
     */
    protected ReferenceMatcher getInstrumentationMuzzle() {
      return null;
    }

    /** @return Class names of helpers to inject into the user's classloader */
    public String[] helperClassNames() {
      return new String[0];
    }

    /**
     * A type matcher used to match the classloader under transform.
     *
     * <p>This matcher needs to either implement equality checks or be the same for different
     * instrumentations that share context stores to avoid enabling the context store
     * instrumentations multiple times.
     *
     * @return A type matcher used to match the classloader under transform.
     */
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
      return ANY_CLASS_LOADER;
    }

    /** @return A type matcher used to match the class under transform. */
    public abstract ElementMatcher<? super TypeDescription> typeMatcher();

    /**
     * A hook invoked after matching has succeeded and before transformers have run.
     *
     * <p>Implementation note: This hook runs inside of the bytebuddy matching phase.
     *
     * @param typeDescription type description of the matched type
     * @param classLoader classloader loading the class under transform
     * @param module java module
     * @param classBeingRedefined null when the matched class is being loaded for the first time.
     *     The instance of the active class during retransforms.
     * @param protectionDomain protection domain of the class under load.
     */
    public void postMatch(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain) {}

    /** @return A map of matcher->advice */
    public abstract Map<? extends ElementMatcher<? super MethodDescription>, String> transformers();

    /**
     * Context stores to define for this instrumentation. Are added to matching class loaders.
     *
     * <p>A map of {class-name -> context-class-name}. Keys (and their subclasses) will be
     * associated with a context of the value.
     */
    public Map<String, String> contextStore() {
      return Collections.emptyMap();
    }

    /**
     * Context stores to define for this instrumentation. Are added to all class loaders.
     *
     * <p>A map of {class-name -> context-class-name}. Keys (and their subclasses) will be
     * associated with a context of the value.
     */
    public Map<String, String> contextStoreForAll() {
      return Collections.emptyMap();
    }

    protected boolean defaultEnabled() {
      return Config.get().isIntegrationsEnabled();
    }

    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return false;
    }
  }

  /** Parent class for all tracing related instrumentations */
  abstract class Tracing extends Default {
    public Tracing(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.TRACING);
    }
  }

  /** Parent class for */
  abstract class Profiling extends Default {
    public Profiling(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.PROFILING);
    }
  }
}
