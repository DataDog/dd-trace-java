package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.Pair;
import java.lang.annotation.AnnotationFormatError;
import java.lang.reflect.Method;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.FixedValue;
import org.junit.jupiter.api.Test;

/**
 * A resource whose annotations are malformed: {@code @Path} is not repeatable, so a method that
 * carries it twice makes reading that method's annotations throw {@link AnnotationFormatError}.
 */
class JaxRsAnnotationsDecoratorTest {

  /** Declares the http method and path the malformed implementation can still be routed by. */
  public interface Orders {
    @GET
    @Path("{id}")
    String get();
  }

  @Test
  void routesByTheInterfaceWhenTheMethodsAnnotationsAreMalformed() throws Exception {
    Class<?> resource = malformedResource(Orders.class);
    Method method = resource.getMethod("get");
    assertThrows(AnnotationFormatError.class, method::getDeclaredAnnotations);

    Pair<CharSequence, CharSequence> httpMethodAndRoute =
        DECORATE.getHttpMethodAndRoute(resource, method);

    assertEquals("GET", String.valueOf(httpMethodAndRoute.getLeft()));
    assertEquals("/orders/{id}", String.valueOf(httpMethodAndRoute.getRight()));
  }

  @Test
  void routesByTheClassPathWhenNothingElseIsReadable() throws Exception {
    Class<?> resource = malformedResource(Object.class);
    Method method = resource.getMethod("get");

    Pair<CharSequence, CharSequence> httpMethodAndRoute =
        DECORATE.getHttpMethodAndRoute(resource, method);

    assertNull(httpMethodAndRoute.getLeft());
    assertEquals("/orders", String.valueOf(httpMethodAndRoute.getRight()));
  }

  /** A {@code @Path("/orders")} class whose {@code get()} carries {@code @GET} and two paths. */
  private static Class<?> malformedResource(Class<?> parent) {
    AnnotationDescription ordersPath = path("/orders");
    AnnotationDescription duplicatedPath = path("{id}");
    AnnotationDescription get = AnnotationDescription.Builder.ofType(GET.class).build();
    ByteBuddy byteBuddy = new ByteBuddy().with(TypeValidation.DISABLED);
    return (parent.isInterface()
            ? byteBuddy.subclass(Object.class).implement(parent)
            : byteBuddy.subclass(parent))
        .annotateType(ordersPath)
        .defineMethod("get", String.class, Visibility.PUBLIC)
        .intercept(FixedValue.value("order"))
        .annotateMethod(get, duplicatedPath, duplicatedPath)
        .make()
        .load(
            JaxRsAnnotationsDecoratorTest.class.getClassLoader(),
            ClassLoadingStrategy.Default.WRAPPER)
        .getLoaded();
  }

  private static AnnotationDescription path(String value) {
    return AnnotationDescription.Builder.ofType(Path.class).define("value", value).build();
  }
}
