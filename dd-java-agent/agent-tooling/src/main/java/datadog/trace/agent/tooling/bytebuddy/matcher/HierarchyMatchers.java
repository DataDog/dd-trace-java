package datadog.trace.agent.tooling.bytebuddy.matcher;

import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.trace.agent.tooling.bytebuddy.outline.OutlinePoolStrategy;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import net.bytebuddy.description.DeclaredByType;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/** Pluggable hierarchy matchers for use with instrumentation matching and muzzle checks. */
public final class HierarchyMatchers {
  private static volatile Supplier SUPPLIER;

  public static ElementMatcher.Junction<TypeDescription> declaresAnnotation(
      NameMatchers.Named<? super NamedElement> matcher) {
    OutlinePoolStrategy.registerAnnotationForMatching(matcher.name);
    return SUPPLIER.declaresAnnotation(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> declaresAnnotation(
      NameMatchers.OneOf<? super NamedElement> matcher) {
    for (String name : matcher.names) {
      OutlinePoolStrategy.registerAnnotationForMatching(name);
    }
    return SUPPLIER.declaresAnnotation(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> declaresField(
      ElementMatcher.Junction<? super FieldDescription> matcher) {
    return SUPPLIER.declaresField(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> declaresMethod(
      ElementMatcher.Junction<? super MethodDescription> matcher) {
    return SUPPLIER.declaresMethod(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> extendsClass(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return SUPPLIER.extendsClass(matcher);
  }

  public static ElementMatcher.Junction<TypeDescription> implementsInterface(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return SUPPLIER.implementsInterface(matcher);
  }

  /**
   * Like {@link #implementsInterface} but also matches when the target type is an interface.
   *
   * <p>Use this when matching return or parameter types that could be classes or interfaces.
   */
  public static ElementMatcher.Junction<TypeDescription> hasInterface(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return SUPPLIER.hasInterface(matcher);
  }

  /** Considers both interfaces and super-classes when matching the target type's hierarchy. */
  public static ElementMatcher.Junction<TypeDescription> hasSuperType(
      ElementMatcher.Junction<? super TypeDescription> matcher) {
    return SUPPLIER.hasSuperType(matcher);
  }

  /** Targets methods whose declaring class has a super-type that declares a matching method. */
  public static ElementMatcher.Junction<MethodDescription> hasSuperMethod(
      ElementMatcher.Junction<? super MethodDescription> matcher) {
    return SUPPLIER.hasSuperMethod(matcher);
  }

  /** Matches classes that should have a field injected for the specified context-store. */
  public static ElementMatcher.Junction<TypeDescription> declaresContextField(
      String keyClassName, String contextClassName) {
    return SUPPLIER.declaresContextField(keyClassName, contextClassName);
  }

  @SuppressForbidden
  public static <T extends AnnotationSource & DeclaredByType.WithMandatoryDeclaration>
      ElementMatcher.Junction<T> isAnnotatedWith(NameMatchers.Named<? super NamedElement> matcher) {
    OutlinePoolStrategy.registerAnnotationForMatching(matcher.name);
    return ElementMatchers.isAnnotatedWith(matcher);
  }

  @SuppressForbidden
  public static <T extends AnnotationSource & DeclaredByType.WithMandatoryDeclaration>
      ElementMatcher.Junction<T> isAnnotatedWith(NameMatchers.OneOf<? super NamedElement> matcher) {
    for (String name : matcher.names) {
      OutlinePoolStrategy.registerAnnotationForMatching(name);
    }
    return ElementMatchers.isAnnotatedWith(matcher);
  }

  public static synchronized void registerIfAbsent(Supplier supplier) {
    if (null == SUPPLIER) {
      SUPPLIER = supplier;
    }
  }

  public interface Supplier {
    ElementMatcher.Junction<TypeDescription> declaresAnnotation(
        ElementMatcher.Junction<? super NamedElement> matcher);

    ElementMatcher.Junction<TypeDescription> declaresField(
        ElementMatcher.Junction<? super FieldDescription> matcher);

    ElementMatcher.Junction<TypeDescription> declaresMethod(
        ElementMatcher.Junction<? super MethodDescription> matcher);

    ElementMatcher.Junction<TypeDescription> extendsClass(
        ElementMatcher.Junction<? super TypeDescription> matcher);

    ElementMatcher.Junction<TypeDescription> implementsInterface(
        ElementMatcher.Junction<? super TypeDescription> matcher);

    ElementMatcher.Junction<TypeDescription> hasInterface(
        ElementMatcher.Junction<? super TypeDescription> matcher);

    ElementMatcher.Junction<TypeDescription> hasSuperType(
        ElementMatcher.Junction<? super TypeDescription> matcher);

    ElementMatcher.Junction<MethodDescription> hasSuperMethod(
        ElementMatcher.Junction<? super MethodDescription> matcher);

    ElementMatcher.Junction<TypeDescription> declaresContextField(
        String keyClassName, String contextClassName);
  }

  /** Simple hierarchy checks for use during the build when testing or validating muzzle ranges. */
  public static HierarchyMatchers.Supplier simpleChecks() {
    return new HierarchyMatchers.Supplier() {
      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> declaresAnnotation(
          ElementMatcher.Junction<? super NamedElement> matcher) {
        return ElementMatchers.isAnnotatedWith(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> declaresField(
          ElementMatcher.Junction<? super FieldDescription> matcher) {
        return ElementMatchers.declaresField(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> declaresMethod(
          ElementMatcher.Junction<? super MethodDescription> matcher) {
        return ElementMatchers.declaresMethod(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> extendsClass(
          ElementMatcher.Junction<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperClass(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> implementsInterface(
          ElementMatcher.Junction<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> hasInterface(
          ElementMatcher.Junction<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<TypeDescription> hasSuperType(
          ElementMatcher.Junction<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public ElementMatcher.Junction<MethodDescription> hasSuperMethod(
          ElementMatcher.Junction<? super MethodDescription> matcher) {
        return ElementMatchers.isDeclaredBy(
            ElementMatchers.hasSuperType(ElementMatchers.declaresMethod(matcher)));
      }

      @Override
      public ElementMatcher.Junction<TypeDescription> declaresContextField(
          String keyClassName, String contextClassName) {
        return none(); // unused during build
      }
    };
  }

  private HierarchyMatchers() {}
}
