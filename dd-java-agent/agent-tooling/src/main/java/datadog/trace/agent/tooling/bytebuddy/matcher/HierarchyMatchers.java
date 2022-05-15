package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.description.annotation.AnnotationSource;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class HierarchyMatchers {

  // Added here instead of byte-buddy's ignores because it's relatively
  // expensive. https://github.com/DataDog/dd-trace-java/pull/1045
  public static final ElementMatcher.Junction<AnnotationSource> NOT_DECORATOR_MATCHER =
      not(isAnnotatedWith(named("javax.decorator.Decorator")));

  private static final AtomicReference<Supplier> SUPPLIER = new AtomicReference<>();

  public static <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().extendsClass(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().implementsInterface(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasSuperType(matcher);
  }

  public static <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
      ElementMatcher<? super MethodDescription> matcher) {
    return SUPPLIER.get().hasSuperMethod(matcher);
  }

  public static <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
      ElementMatcher<? super TypeDescription> matcher) {
    return SUPPLIER.get().hasInterface(matcher);
  }

  public static void registerIfAbsent(Supplier supplier) {
    SUPPLIER.compareAndSet(null, supplier);
  }

  public interface Supplier {
    <T extends TypeDescription> ElementMatcher.Junction<T> extendsClass(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> implementsInterface(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> hasSuperType(
        ElementMatcher<? super TypeDescription> matcher);

    <T extends MethodDescription> ElementMatcher.Junction<T> hasSuperMethod(
        ElementMatcher<? super MethodDescription> matcher);

    <T extends TypeDescription> ElementMatcher.Junction<T> hasInterface(
        ElementMatcher<? super TypeDescription> matcher);
  }

  private HierarchyMatchers() {}
}
