package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

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

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasInterface(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasSuperType(matcher);
  }

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

  private HierarchyMatchers() {}
}
