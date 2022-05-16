package datadog.trace.agent.tooling.bytebuddy.matcher;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/** Pluggable hierarchy matchers for use with instrumentation matching and muzzle checks. */
public final class HierarchyMatchers {
  private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

  public static <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().extendsClass(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().implementsInterface(matcher);
  }

  public static <T extends AnnotationSource> ElementMatcher.Junction<T> isAnnotatedWith(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().isAnnotatedWith(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> declaresField(
      ElementMatcher<? super FieldDescription> matcher) {
    return SUPPLIER.get().declaresField(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> declaresMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return SUPPLIER.get().declaresMethod(matcher);
  }

  /**
   * Like {@link #implementsInterface} but also matches when the target type is an interface.
   *
   * <p>Use this when matching return or parameter types that could be classes or interfaces.
   */
  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasInterface(matcher);
  }

  /** Considers both interfaces and super-classes when matching the target type's hierarchy. */
  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasSuperType(matcher);
  }

  /** Targets methods whose declaring class has a super-type that declares a matching method. */
  public static <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return SUPPLIER.get().hasSuperMethod(matcher);
  }

  public static void registerIfAbsent(Supplier supplier) {
    SUPPLIER.compareAndSet(null, supplier);
  }

  public interface Supplier {
    <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends AnnotationSource> ElementMatcher.Junction<T> isAnnotatedWith(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> declaresField(
        ElementMatcher<? super FieldDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> declaresMethod(
        ElementMatcher<? super MethodDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
        ElementMatcher<? super MethodDescription> matcher);
  }

  /** Simple hierarchy checks for use during the build when testing or validating muzzle ranges. */
  public static HierarchyMatchers.Supplier simpleChecks() {
    return new HierarchyMatchers.Supplier() {
      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
          ElementMatcher<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperClass(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
          ElementMatcher<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends AnnotationSource> ElementMatcher.Junction<T> isAnnotatedWith(
          ElementMatcher<? super TypeDescription> matcher) {
        return ElementMatchers.isAnnotatedWith(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> declaresField(
          ElementMatcher<? super FieldDescription> matcher) {
        return ElementMatchers.declaresField(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> declaresMethod(
          ElementMatcher<? super MethodDescription> matcher) {
        return ElementMatchers.declaresMethod(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
          ElementMatcher<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
          ElementMatcher<? super TypeDescription> matcher) {
        return ElementMatchers.hasSuperType(matcher);
      }

      @Override
      @SuppressForbidden
      public <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
          ElementMatcher<? super MethodDescription> matcher) {
        return ElementMatchers.isDeclaredBy(
            ElementMatchers.hasSuperType(ElementMatchers.declaresMethod(matcher)));
      }
    };
  }

  private HierarchyMatchers() {}
}
