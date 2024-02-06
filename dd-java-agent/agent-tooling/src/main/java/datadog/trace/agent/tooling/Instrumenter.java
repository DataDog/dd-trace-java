package datadog.trace.agent.tooling;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.List;
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
 * <p>It is strongly recommended to extend {@link InstrumenterGroup} rather than implement this
 * interface directly.
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
  interface HasTypeAdvice extends Instrumenter {
    /**
     * Instrumenters should register the full type advice with {@link
     * TypeTransformer#applyAdvice(TransformingAdvice)}.
     */
    void typeAdvice(TypeTransformer transformer);
  }

  /** Instrumentation that provides advice specific to one or more methods. */
  interface HasMethodAdvice extends Instrumenter {
    /**
     * Instrumenters should register each method advice with {@link
     * MethodTransformer#applyAdvice(ElementMatcher, String)}.
     */
    void methodAdvice(MethodTransformer transformer);
  }

  /** Instrumentation that transforms types on the bootstrap class-path. */
  interface ForBootstrap {}

  /** Parent class for all tracing related instrumentations */
  abstract class Tracing extends InstrumenterGroup implements HasMethodAdvice {
    public Tracing(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.TRACING);
    }
  }

  /** Parent class for all profiling related instrumentations */
  abstract class Profiling extends InstrumenterGroup implements HasMethodAdvice {
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
  abstract class AppSec extends InstrumenterGroup implements HasMethodAdvice {
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
  abstract class Iast extends InstrumenterGroup implements HasMethodAdvice, WithPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(Instrumenter.Iast.class);

    public Iast(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public List<Instrumenter> typeInstrumentations() {
      preloadClassNames();
      return super.typeInstrumentations();
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
  abstract class Usm extends InstrumenterGroup implements HasMethodAdvice {
    public Usm(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.USM);
    }
  }

  /** Parent class for all CI related instrumentations */
  abstract class CiVisibility extends InstrumenterGroup implements HasMethodAdvice {

    public CiVisibility(String instrumentationName, String... additionalNames) {
      super(instrumentationName, additionalNames);
    }

    @Override
    public boolean isApplicable(Set<TargetSystem> enabledSystems) {
      return enabledSystems.contains(TargetSystem.CIVISIBILITY);
    }
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
