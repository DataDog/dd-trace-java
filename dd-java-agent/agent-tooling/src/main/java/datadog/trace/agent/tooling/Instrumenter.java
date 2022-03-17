package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.ANY_CLASS_LOADER;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;

import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
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
   * Instrumenter} type must declare its target system. Four systems are currently supported
   *
   * <ul>
   *   <li>{@link TargetSystem#TRACING tracing}
   *   <li>{@link TargetSystem#PROFILING profiling}
   *   <li>{@link TargetSystem#APPSEC appsec}
   *   <li>{@link TargetSystem#CIVISIBILITY ci-visibility}
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

  /** Instrumentation that provides method advice. */
  interface HasAdvice {
    /**
     * Instrumenters should register each advice transformation by calling {@link
     * AdviceTransformation#applyAdvice(ElementMatcher, String)} one or more times.
     */
    void adviceTransformations(AdviceTransformation transformation);
  }

  /**
   * Indicates the applicability of an {@linkplain Instrumenter} to the given system.<br>
   *
   * @param enabledSystems a set of all the enabled target systems
   * @return {@literal true} if the set of enabled systems contains all the ones required by this
   *     particular {@linkplain Instrumenter}
   */
  boolean isApplicable(Set<TargetSystem> enabledSystems);

  /**
   * Adds this instrumentation to a {@link TransformerBuilder}.
   *
   * @param transformerBuilder builds instrumentations into a class transformer.
   */
  void instrument(TransformerBuilder transformerBuilder);

  @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
  abstract class Default implements Instrumenter, HasAdvice {
    private static final Logger log = LoggerFactory.getLogger(Default.class);

    private final List<String> instrumentationNames;
    private final String instrumentationPrimaryName;
    private final boolean enabled;

    protected final String packageName =
        getClass().getPackage() == null ? "" : getClass().getPackage().getName();

    public Default(final String instrumentationName, final String... additionalNames) {
      instrumentationNames = new ArrayList<>(1 + additionalNames.length);
      instrumentationNames.add(instrumentationName);
      addAll(instrumentationNames, additionalNames);
      instrumentationPrimaryName = instrumentationName;

      enabled = Config.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
    }

    public String name() {
      return instrumentationPrimaryName;
    }

    public Iterable<String> names() {
      return instrumentationNames;
    }

    @Override
    public final void instrument(TransformerBuilder transformerBuilder) {
      if (isEnabled()) {
        transformerBuilder.applyInstrumentation(this);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(
              "Disabled - instrumentation.names=[{}] instrumentation.class={}",
              Strings.join(",", instrumentationNames),
              getClass().getName());
        }
      }
    }

    /** Matches classes for which instrumentation is not muzzled. */
    public final boolean muzzleMatches(
        final ClassLoader classLoader, final Class<?> classBeingRedefined) {
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
                "Muzzled - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={}",
                Strings.join(",", instrumentationNames),
                Instrumenter.Default.this.getClass().getName(),
                classLoader);
            for (final Reference.Mismatch mismatch : mismatches) {
              log.debug(
                  "Muzzled mismatch - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={} muzzle.mismatch=\"{}\"",
                  Strings.join(",", instrumentationNames),
                  Instrumenter.Default.this.getClass().getName(),
                  classLoader,
                  mismatch);
            }
          }
        } else {
          if (log.isDebugEnabled()) {
            log.debug(
                "Instrumentation applied - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={} instrumentation.target.class={}",
                Strings.join(",", instrumentationNames),
                Instrumenter.Default.this.getClass().getName(),
                classLoader,
                classBeingRedefined == null ? "null" : classBeingRedefined.getName());
          }
        }
        return isMatch;
      }
      return true;
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

  interface TransformerBuilder {
    void applyInstrumentation(HasAdvice instrumenter);
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
