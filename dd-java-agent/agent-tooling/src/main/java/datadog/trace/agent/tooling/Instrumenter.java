package datadog.trace.agent.tooling;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.Set;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Declares bytebuddy-based type instrumentation for the datadog javaagent. */
public interface Instrumenter {

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

  /** Instrumentation that transforms types on the bootstrap class-path. */
  interface ForBootstrap {}

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

  /** Instrumentation that provides advice which affects the whole type. */
  interface HasTypeAdvice extends Instrumenter {
    /**
     * Instrumenters should register the full type advice with {@link
     * TypeTransformer#applyAdvice(TransformingAdvice)}.
     */
    void typeAdvice(TypeTransformer transformer);
  }

  interface HasGeneralPurposeAdvices extends Instrumenter {
    Set<String> generalPurposeAdviceClasses();
  }

  /** Instrumentation that provides advice specific to one or more methods. */
  interface HasMethodAdvice extends Instrumenter {
    /**
     * Instrumenters should register each method advice with {@link
     * MethodTransformer#applyAdvice(ElementMatcher, String)}.
     */
    void methodAdvice(MethodTransformer transformer);
  }

  /** Applies type advice from an instrumentation that {@link HasTypeAdvice}. */
  interface TypeTransformer {
    void applyAdvice(TransformingAdvice typeAdvice);

    default void applyAdvice(AsmVisitorWrapper typeVisitor) {
      applyAdvice(new VisitingAdvice(typeVisitor));
    }
  }

  /** Applies method advice from an instrumentation that {@link HasMethodAdvice}. */
  interface MethodTransformer {
    void applyAdvice(
        ElementMatcher<? super MethodDescription> matcher,
        String adviceClass,
        String... additionalAdviceClasses);
  }

  /** Contributes a transformation step to the dynamic type builder. */
  interface TransformingAdvice {
    DynamicType.Builder<?> transform(
        DynamicType.Builder<?> builder,
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        ProtectionDomain pd);
  }
}
