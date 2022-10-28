package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;

import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.ProtectionDomain;
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
   * Since several subsystems are sharing the same instrumentation infrastructure in order to enable
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
    IAST,
    CIVISIBILITY
  }

  /** Instrumentation that only matches a single named type. */
  interface ForSingleType {
    String instrumentedType();
  }

  /** Instrumentation that matches a type configured at runtime. */
  interface ForConfiguredType {
    String configuredMatchingType();
  }

  /** Instrumentation that can match a series of named types. */
  interface ForKnownTypes {
    String[] knownMatchingTypes();
  }

  /** Instrumentation that matches based on the type hierarchy. */
  interface ForTypeHierarchy {
    /** Hint that class-loaders without this type can skip this hierarchy matcher. */
    String hierarchyMarkerType();

    ElementMatcher<TypeDescription> hierarchyMatcher();
  }

  /** Instrumentation that matches based on the caller of an instruction. */
  interface ForCallSite {
    ElementMatcher<TypeDescription> callerType();
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

  /** Instrumentation that transforms types on the bootstrap class-path. */
  interface ForBootstrap {}

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

    private final int instrumentationId;
    private final List<String> instrumentationNames;
    private final String instrumentationPrimaryName;
    private final boolean enabled;

    protected final String packageName = Strings.getPackageName(getClass().getName());

    public Default(final String instrumentationName, final String... additionalNames) {
      instrumentationId = Instrumenters.currentInstrumentationId();
      instrumentationNames = new ArrayList<>(1 + additionalNames.length);
      instrumentationNames.add(instrumentationName);
      addAll(instrumentationNames, additionalNames);
      instrumentationPrimaryName = instrumentationName;

      enabled = Config.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
    }

    public int instrumentationId() {
      return instrumentationId;
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
      // Optimization: we delay calling getInstrumentationMuzzle() until we need the references
      ReferenceMatcher muzzle = getInstrumentationMuzzle();
      if (null != muzzle) {
        final boolean isMatch = muzzle.matches(classLoader);
        if (!isMatch) {
          if (log.isDebugEnabled()) {
            final List<Reference.Mismatch> mismatches =
                muzzle.getMismatchedReferenceSources(classLoader);
            log.debug(
                "Muzzled - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={}",
                Strings.join(",", instrumentationNames),
                getClass().getName(),
                classLoader);
            for (final Reference.Mismatch mismatch : mismatches) {
              log.debug(
                  "Muzzled mismatch - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={} muzzle.mismatch=\"{}\"",
                  Strings.join(",", instrumentationNames),
                  getClass().getName(),
                  classLoader,
                  mismatch);
            }
          }
        } else {
          if (log.isDebugEnabled()) {
            log.debug(
                "Instrumentation applied - instrumentation.names=[{}] instrumentation.class={} instrumentation.target.classloader={} instrumentation.target.class={}",
                Strings.join(",", instrumentationNames),
                getClass().getName(),
                classLoader,
                classBeingRedefined == null ? "null" : classBeingRedefined.getName());
          }
        }
        return isMatch;
      }
      return true;
    }

    public final ReferenceMatcher getInstrumentationMuzzle() {
      String muzzleClassName = getClass().getName() + "$Muzzle";
      try {
        // Muzzle class contains static references captured at build-time
        // see datadog.trace.agent.tooling.muzzle.MuzzleGenerator
        ReferenceMatcher muzzle =
            (ReferenceMatcher)
                getClass()
                    .getClassLoader()
                    .loadClass(muzzleClassName)
                    .getConstructor()
                    .newInstance();
        // mix in any additional references captured at runtime
        muzzle.withReferenceProvider(runtimeMuzzleReferences());
        return muzzle;
      } catch (Throwable e) {
        log.warn("Failed to load - muzzle.class={}", muzzleClassName, e);
        return null;
      }
    }

    /** @return Class names of helpers to inject into the user's classloader */
    public String[] helperClassNames() {
      return new String[0];
    }

    /** Classes that the muzzle plugin assumes will be injected */
    public String[] muzzleIgnoredClassNames() {
      return helperClassNames();
    }

    /** Override this to supply additional Muzzle references at build time. */
    public Reference[] additionalMuzzleReferences() {
      return null;
    }

    /** Override this to supply additional Muzzle references during startup. */
    public ReferenceProvider runtimeMuzzleReferences() {
      return null;
    }

    /** Override this to validate against a specific named MuzzleDirective. */
    public String muzzleDirective() {
      return null;
    }

    /** Override this to supply additional class-loader requirements. */
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

  /** Parent class for all IAST related instrumentations */
  abstract class Iast extends Default {
    public Iast(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.IAST);
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
        JavaModule module,
        ProtectionDomain pd);
  }
}
