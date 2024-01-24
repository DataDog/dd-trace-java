package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.ANY_CLASS_LOADER;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;

import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceProvider;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.Strings;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
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
   * Since several systems share the same instrumentation infrastructure in order to enable only the
   * applicable {@link Instrumenter instrumenters} on startup each {@linkplain Instrumenter} type
   * must declare its target system. Five systems are currently supported:
   *
   * <ul>
   *   <li>{@link TargetSystem#TRACING tracing}
   *   <li>{@link TargetSystem#PROFILING profiling}
   *   <li>{@link TargetSystem#APPSEC appsec}
   *   <li>{@link TargetSystem#IAST iast}
   *   <li>{@link TargetSystem#CIVISIBILITY ci-visibility}
   *   <li>{@link TargetSystem#USM usm}
   * </ul>
   */
  enum TargetSystem {
    TRACING,
    PROFILING,
    APPSEC,
    IAST,
    CIVISIBILITY,

    USM
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
    /** Hint that class-loaders without this type can skip this hierarchy matcher. */
    String hierarchyMarkerType();

    ElementMatcher<TypeDescription> hierarchyMatcher();
  }

  /**
   * Instrumentation that matches a series of types configured at runtime. This is used for last
   * minute additions in the field such as testing a new JDBC driver that is not yet in the allowed
   * list and to provide a workaround until the next release. The ForKnownTypes interface is more
   * appropriate when you know the series of types at build-time.
   */
  interface ForConfiguredTypes {
    Collection<String> configuredMatchingTypes();
  }

  /**
   * Instrumentation that matches an optional type configured at runtime. This is used for last
   * minute additions in the field such as testing a new JDBC driver that is not yet in the allowed
   * list and to provide a workaround until the next release. The ForSingleType interface is more
   * appropriate when you know the type at build-time.
   */
  interface ForConfiguredType extends ForConfiguredTypes {
    @Override
    default Collection<String> configuredMatchingTypes() {
      String type = configuredMatchingType();
      if (null != type && !type.isEmpty()) {
        return singletonList(type);
      } else {
        return emptyList();
      }
    }

    String configuredMatchingType();
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
    ElementMatcher<TypeDescription> structureMatcher();
  }

  /** Instrumentation that wants to apply additional structure checks after type matching. */
  interface WithPostProcessor {
    Advice.PostProcessor.Factory postProcessor();
  }

  /** Instrumentation that provides advice which affects the whole type. */
  interface HasTypeAdvice {
    /**
     * Instrumenters should register the full type advice with {@link
     * TypeTransformer#applyAdvice(TransformingAdvice)}.
     */
    void typeAdvice(TypeTransformer transformer);
  }

  /** Instrumentation that provides advice specific to one or more methods. */
  interface HasMethodAdvice {
    /**
     * Instrumenters should register each method advice with {@link
     * MethodTransformer#applyAdvice(ElementMatcher, String)}.
     */
    void methodAdvice(MethodTransformer transformer);
  }

  interface HasAdvice extends HasTypeAdvice, HasMethodAdvice {}

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

      enabled =
          InstrumenterConfig.get().isIntegrationEnabled(instrumentationNames, defaultEnabled());
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
    public void instrument(TransformerBuilder transformerBuilder) {
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

    public final ReferenceMatcher getInstrumentationMuzzle() {
      return loadStaticMuzzleReferences(getClass().getClassLoader(), getClass().getName())
          .withReferenceProvider(runtimeMuzzleReferences());
    }

    public static ReferenceMatcher loadStaticMuzzleReferences(
        ClassLoader classLoader, String instrumentationClass) {
      String muzzleClass = instrumentationClass + "$Muzzle";
      try {
        // Muzzle class contains static references captured at build-time
        // see datadog.trace.agent.tooling.muzzle.MuzzleGenerator
        return (ReferenceMatcher)
            classLoader.loadClass(muzzleClass).getMethod("create").invoke(null);
      } catch (Throwable e) {
        log.warn("Failed to load - muzzle.class={}", muzzleClass, e);
        return ReferenceMatcher.NO_REFERENCES;
      }
    }

    /** @return Class names of helpers to inject into the user's classloader */
    public String[] helperClassNames() {
      return new String[0];
    }

    /** Override this to automatically inject all (non-bootstrap) helper dependencies. */
    public boolean injectHelperDependencies() {
      return false;
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

    /** Override this to register full type transformations. */
    @Override
    public void typeAdvice(TypeTransformer transformer) {}

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
      return InstrumenterConfig.get().isIntegrationsEnabled();
    }

    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return false;
    }

    protected final boolean isShortcutMatchingEnabled(boolean defaultToShortcut) {
      return InstrumenterConfig.get()
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

    @Override
    public boolean isEnabled() {
      return super.isEnabled()
          && !ConfigProvider.getInstance()
              .getBoolean(ProfilingConfig.PROFILING_ULTRA_MINIMAL, false);
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
  @SuppressForbidden
  abstract class Iast extends Default implements WithPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(Instrumenter.Iast.class);

    public Iast(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public void instrument(TransformerBuilder transformerBuilder) {
      if (isEnabled()) {
        preloadClassNames();
      }
      super.instrument(transformerBuilder);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.IAST);
    }

    /**
     * Force loading of classes that need to be instrumented, but are using during instrumentation.
     */
    private void preloadClassNames() {
      String[] list = getClassNamesToBePreloaded();
      if (list != null) {
        for (String clazz : list) {
          try {
            Class.forName(clazz);
          } catch (Throwable t) {
            log.debug("Error force loading {} class", clazz);
          }
        }
      }
    }

    /** Get classes to force load* */
    public String[] getClassNamesToBePreloaded() {
      return null;
    }

    @Override
    public Advice.PostProcessor.Factory postProcessor() {
      return IastPostProcessorFactory.INSTANCE;
    }
  }

  /** Parent class for all USM related instrumentations */
  abstract class Usm extends Default {
    public Usm(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.USM);
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

    ClassFileTransformer installOn(Instrumentation instrumentation);
  }

  interface TypeTransformer {
    void applyAdvice(TransformingAdvice typeAdvice);

    default void applyAdvice(AsmVisitorWrapper typeVisitor) {
      applyAdvice(new VisitingAdvice(typeVisitor));
    }
  }

  interface MethodTransformer {
    void applyAdvice(ElementMatcher<? super MethodDescription> matcher, String className);
  }

  interface TransformingAdvice {
    DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        ProtectionDomain pd);
  }

  final class VisitingAdvice implements TransformingAdvice {
    private final AsmVisitorWrapper visitor;

    public VisitingAdvice(AsmVisitorWrapper visitor) {
      this.visitor = visitor;
    }

    @Override
    public DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        ProtectionDomain pd) {
      return builder.visit(visitor);
    }
  }
}
