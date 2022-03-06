package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.DDTransformers.defaultTransformers;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.agent.tooling.bytebuddy.matcher.AsyncMatching;
import datadog.trace.agent.tooling.bytebuddy.matcher.FailSafeRawMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.KnownTypesMatcher;
import datadog.trace.agent.tooling.bytebuddy.matcher.SingleTypeMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextProvider;
import datadog.trace.agent.tooling.context.InstrumentationContextProvider;
import datadog.trace.agent.tooling.context.NoopContextProvider;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatcher.Junction;
import net.bytebuddy.matcher.ElementMatcher.Junction.Conjunction;
import net.bytebuddy.matcher.ElementMatcher.Junction.Disjunction;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
   * Instrumenter} type must declare its target system. Currently only three systems are supported
   *
   * <ul>
   *   <li>{@link TargetSystem#TRACING tracing}
   *   <li>{@link TargetSystem#PROFILING profiling}
   *   <li>{@link TargetSystem#APPSEC appsec}
   * </ul>
   */
  enum TargetSystem {
    TRACING,
    PROFILING,
    APPSEC,
    CIVISIBILITY
  }

  /** Instrumentation that only matches a single named type. */
  interface ForSingleType {
    String instrumentedType();
  }

  /** Instrumentation that can match a series of named types. */
  interface ForKnownTypes {
    String[] knownMatchingTypes();
  }

  /** Instrumentation that matches based on the type hierarchy. */
  interface ForTypeHierarchy {
    ElementMatcher<TypeDescription> hierarchyMatcher();
  }

  /** Instrumentation that can optionally widen matching to consider the type hierarchy. */
  interface CanShortcutTypeMatching extends ForKnownTypes, ForTypeHierarchy {
    boolean onlyMatchKnownTypes();
  }

  /** Instrumentation that wants to apply additional structure checks after type matching. */
  interface WithTypeStructure {
    ElementMatcher<? extends ByteCodeElement> structureMatcher();
  }

  /**
   * Add this instrumentation to an AgentBuilder.
   *
   * @param agentBuilder AgentBuilder to base instrumentation config off of.
   * @param asyncMatching Optional utility that can make matchers asynchronous.
   * @return the original agentBuilder and this instrumentation
   */
  AgentBuilder instrument(AgentBuilder agentBuilder, AsyncMatching asyncMatching);

  /**
   * Indicates the applicability of an {@linkplain Instrumenter} to the given system.<br>
   *
   * @param enabledSystems a set of all the enabled target systems
   * @return {@literal true} if the set of enabled systems contains all the ones required by this
   *     particular {@linkplain Instrumenter}
   */
  boolean isApplicable(Set<TargetSystem> enabledSystems);

  @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
  abstract class Default implements Instrumenter {
    private static final Logger log = LoggerFactory.getLogger(Default.class);
    private static final ElementMatcher<ClassLoader> ANY_CLASS_LOADER = any();

    // Added here instead of AgentInstaller's ignores because it's relatively
    // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
    public static final Junction<AnnotationSource> NOT_DECORATOR_MATCHER =
        not(isAnnotatedWith(named("javax.decorator.Decorator")));

    private static final boolean ASYNC_MATCHING_ENABLED = Config.get().isAsyncMatchingEnabled();

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

    public String name() {
      return instrumentationPrimaryName;
    }

    // Since the super(...) call is first in the constructor, we can't really rely on things
    // being properly initialized in the Instrumentation until the super(...) call has finished
    // so do the rest of the initialization lazily
    private void lazyInit() {
      synchronized (this) {
        if (!initialized) {
          Map<String, String> matchedContextStores = contextStore();
          if (matchedContextStores.isEmpty()) {
            contextProvider = NoopContextProvider.INSTANCE;
          } else {
            contextProvider =
                new FieldBackedContextProvider(
                    this, singletonMap(classLoaderMatcher(), matchedContextStores));
          }
          initialized = true;
        }
      }
    }

    @Override
    public final AgentBuilder instrument(
        final AgentBuilder parentAgentBuilder, final AsyncMatching asyncMatching) {
      if (!isEnabled()) {
        log.debug("Instrumentation {} is disabled", this);
        return parentAgentBuilder;
      }

      lazyInit();

      AgentBuilder.Identified.Extendable agentBuilder =
          filter(parentAgentBuilder, asyncMatching).transform(defaultTransformers());
      agentBuilder = injectHelperClasses(agentBuilder);
      agentBuilder = contextProvider.instrumentationTransformer(agentBuilder);
      final AdviceTransformer customTransformer = transformer();
      if (customTransformer != null) {
        agentBuilder =
            agentBuilder.transform(
                new AgentBuilder.Transformer() {
                  @Override
                  public DynamicType.Builder<?> transform(
                      DynamicType.Builder<?> builder,
                      TypeDescription typeDescription,
                      ClassLoader classLoader,
                      JavaModule module) {
                    return customTransformer.transform(
                        builder, typeDescription, classLoader, module);
                  }
                });
      }
      AdviceBuilder adviceBuilder = new AdviceBuilder(agentBuilder, methodIgnoreMatcher());
      adviceTransformations(adviceBuilder);
      agentBuilder = adviceBuilder.agentBuilder;
      agentBuilder = contextProvider.additionalInstrumentation(agentBuilder);
      return agentBuilder;
    }

    private AgentBuilder.Identified.Narrowable filter(
        AgentBuilder agentBuilder, AsyncMatching asyncMatching) {
      ElementMatcher<? super TypeDescription> typeMatcher;
      if (this instanceof ForSingleType) {
        typeMatcher = new SingleTypeMatcher(((ForSingleType) this).instrumentedType());
      } else if (this instanceof ForKnownTypes) {
        typeMatcher = new KnownTypesMatcher(((ForKnownTypes) this).knownMatchingTypes());
      } else if (this instanceof ForTypeHierarchy) {
        typeMatcher = ((ForTypeHierarchy) this).hierarchyMatcher();
      } else {
        return agentBuilder.type(AgentBuilder.RawMatcher.Trivial.NON_MATCHING);
      }

      if (this instanceof CanShortcutTypeMatching
          && !((CanShortcutTypeMatching) this).onlyMatchKnownTypes()) {
        // not taking shortcuts, so include wider hierarchical matching
        typeMatcher = new Disjunction(typeMatcher, ((ForTypeHierarchy) this).hierarchyMatcher());
      }

      if (this instanceof WithTypeStructure) {
        // only perform structure matching after we've matched the type
        typeMatcher = new Conjunction(typeMatcher, ((WithTypeStructure) this).structureMatcher());
      }

      ElementMatcher<ClassLoader> classLoaderMatcher = classLoaderMatcher();

      AgentBuilder.RawMatcher rawMatcher;
      if (classLoaderMatcher == ANY_CLASS_LOADER
          && typeMatcher instanceof AgentBuilder.RawMatcher) {
        // optimization when using raw (named) type matcher with no classloader filtering
        rawMatcher = (AgentBuilder.RawMatcher) typeMatcher;
      } else if (typeMatcher instanceof SingleTypeMatcher) {
        rawMatcher = ((SingleTypeMatcher) typeMatcher).with(classLoaderMatcher);
      } else if (typeMatcher instanceof KnownTypesMatcher) {
        rawMatcher = ((KnownTypesMatcher) typeMatcher).with(classLoaderMatcher);
      } else {
        rawMatcher =
            new FailSafeRawMatcher(
                typeMatcher,
                classLoaderMatcher,
                "Instrumentation matcher unexpected exception: " + getClass().getName());

        if (ASYNC_MATCHING_ENABLED) {
          rawMatcher = asyncMatching.makeAsync(rawMatcher);
        }
      }

      return agentBuilder.type(rawMatcher).and(NOT_DECORATOR_MATCHER).and(new MuzzleMatcher());
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

    private static class AdviceBuilder implements AdviceTransformation {
      AgentBuilder.Identified.Extendable agentBuilder;
      final ElementMatcher<? super MethodDescription> ignoreMatcher;

      public AdviceBuilder(
          AgentBuilder.Identified.Extendable agentBuilder,
          ElementMatcher<? super MethodDescription> ignoreMatcher) {
        this.agentBuilder = agentBuilder;
        this.ignoreMatcher = ignoreMatcher;
      }

      @Override
      public void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String name) {
        agentBuilder =
            agentBuilder.transform(
                new AgentBuilder.Transformer.ForAdvice()
                    .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                    .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                    .advice(not(ignoreMatcher).and(matcher), name));
      }
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
        final IReferenceMatcher muzzle = getInstrumentationMuzzle();
        if (null != muzzle) {
          final boolean isMatch = muzzle.matches(classLoader);
          if (!isMatch) {
            if (log.isDebugEnabled()) {
              final List<Reference.Mismatch> mismatches =
                  muzzle.getMismatchedReferenceSources(classLoader);
              log.debug(
                  "Instrumentation muzzled: {} -- {} on {}",
                  instrumentationNames,
                  Instrumenter.Default.this.getClass().getName(),
                  classLoader);
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

    /**
     * This method is implemented dynamically by compile-time bytecode transformations.
     *
     * <p>{@see datadog.trace.agent.tooling.muzzle.MuzzleGradlePlugin}
     */
    protected IReferenceMatcher getInstrumentationMuzzle() {
      return null;
    }

    /** @return Class names of helpers to inject into the user's classloader */
    public String[] helperClassNames() {
      return new String[0];
    }

    /* Classes that the muzzle plugin assumes will be injected */
    public String[] muzzleIgnoredClassNames() {
      return helperClassNames();
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

    /** @return A transformer for further transformation of the class */
    public AdviceTransformer transformer() {
      return null;
    }

    /** @return A type matcher used to ignore some methods when applying transformation. */
    public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
      // By default ByteBuddy will skip all methods that are synthetic at the top level, but since
      // we need to instrument some synthetic methods in Scala and changed that, we make the default
      // here to ignore synthetic methods to not change the behavior for unaware instrumentations
      return isSynthetic();
    }

    /**
     * Instrumenters should register each advice transformation by calling {@link
     * AdviceTransformation#applyAdvice(ElementMatcher, String)} one or more times.
     */
    public abstract void adviceTransformations(AdviceTransformation transformation);

    /**
     * Context stores to define for this instrumentation. Are added to matching class loaders.
     *
     * <p>A map of {class-name -> context-class-name}. Keys (and their subclasses) will be
     * associated with a context of the value.
     */
    public Map<String, String> contextStore() {
      return emptyMap();
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

    protected final boolean isShortcutMatchingEnabled(boolean defaultToShortcut) {
      return Config.get()
          .isIntegrationShortcutMatchingEnabled(singletonList(name()), defaultToShortcut);
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

  /** Parent class for all profiling related instrumentations */
  abstract class Profiling extends Default {
    public Profiling(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.PROFILING);
    }
  }

  /** Parent class for all AppSec related instrumentations */
  abstract class AppSec extends Default {
    public AppSec(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.APPSEC);
    }
  }

  /** Parent class for all CI related instrumentations */
  abstract class CiVisibility extends Default {

    public CiVisibility(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.CIVISIBILITY);
    }
  }

  interface AdviceTransformation {
    void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String name);
  }

  interface AdviceTransformer {
    DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module);
  }
}
