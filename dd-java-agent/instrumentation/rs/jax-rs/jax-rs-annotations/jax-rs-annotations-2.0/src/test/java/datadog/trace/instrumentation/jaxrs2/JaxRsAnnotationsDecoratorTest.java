package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.api.KnownTags;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.annotation.AnnotationFormatError;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
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

  /** The route a subclass falls back to when its superclass's class annotations are malformed. */
  @Path("/parent")
  public interface Parent {
    @GET
    @Path("{id}")
    String get();
  }

  /**
   * A class's annotations are read together with those it inherits, so a superclass carrying two
   * {@code @Path}s makes the subclass's own {@code @Path} unreadable too, and the route falls back
   * to the interface's. Pinned either way, in case a JVM reads the subclass's own annotations.
   */
  @Test
  void aMalformedSuperclassFallsBackToTheInterfacesClassPath() throws Exception {
    Class<?> malformedBase =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(Object.class)
            .implement(Parent.class)
            .annotateType(path("/base"), path("/base"))
            .defineMethod("get", String.class, Visibility.PUBLIC)
            .intercept(FixedValue.value("order"))
            .make()
            .load(
                JaxRsAnnotationsDecoratorTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    Class<?> resource =
        new ByteBuddy()
            .subclass(malformedBase)
            .annotateType(path("/orders"))
            .make()
            .load(malformedBase.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    assertThrows(AnnotationFormatError.class, () -> malformedBase.getAnnotation(Path.class));
    boolean subclassReadable = isReadable(resource);

    Pair<CharSequence, CharSequence> httpMethodAndRoute =
        DECORATE.getHttpMethodAndRoute(resource, resource.getMethod("get"));

    assertEquals("GET", String.valueOf(httpMethodAndRoute.getLeft()));
    assertEquals(
        subclassReadable ? "/orders/{id}" : "/parent/{id}",
        String.valueOf(httpMethodAndRoute.getRight()));
  }

  private static boolean isReadable(Class<?> type) {
    try {
      type.getAnnotation(Path.class);
      return true;
    } catch (AnnotationFormatError e) {
      return false;
    }
  }

  /**
   * The method's own annotations read fine, but one of their annotation types carries malformed
   * meta-annotations; the http method is still found on the annotations after it.
   */
  @Test
  void findsTheHttpMethodPastAnAnnotationTypeWithMalformedMetaAnnotations() throws Exception {
    // Described from ByteBuddy's unloaded form: writing the resource then doesn't read the
    // annotation type's (malformed) annotations through reflection.
    DynamicType.Unloaded<?> malformedType =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .makeAnnotation()
            .name("datadog.trace.instrumentation.jaxrs2.MalformedMeta")
            .annotateType(
                AnnotationDescription.Builder.ofType(Retention.class)
                    .define("value", RetentionPolicy.RUNTIME)
                    .build(),
                path("/meta"),
                path("/meta"))
            .make();
    Class<?> resource =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(Object.class)
            .annotateType(path("/orders"))
            .defineMethod("get", String.class, Visibility.PUBLIC)
            .intercept(FixedValue.value("order"))
            .annotateMethod(
                AnnotationDescription.Builder.ofType(malformedType.getTypeDescription()).build(),
                AnnotationDescription.Builder.ofType(GET.class).build(),
                path("{id}"))
            .make()
            .include(malformedType)
            .load(
                JaxRsAnnotationsDecoratorTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    Class<?> loadedType =
        resource.getClassLoader().loadClass(malformedType.getTypeDescription().getName());
    assertThrows(AnnotationFormatError.class, () -> loadedType.getAnnotation(HttpMethod.class));

    Pair<CharSequence, CharSequence> httpMethodAndRoute =
        DECORATE.getHttpMethodAndRoute(resource, resource.getMethod("get"));

    assertEquals("GET", String.valueOf(httpMethodAndRoute.getLeft()));
    assertEquals("/orders/{id}", String.valueOf(httpMethodAndRoute.getRight()));
  }

  /**
   * With every @Path unreadable and a readable @GET, there's no route: the span keeps its name
   * rather than being renamed to "GET " with an empty http.route.
   */
  @Test
  void keepsTheSpanNameWhenNoPathIsReadable() throws Exception {
    Class<?> resource =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(Object.class)
            .annotateType(path("/orders"), path("/orders"))
            .defineMethod("get", String.class, Visibility.PUBLIC)
            .intercept(FixedValue.value("order"))
            .annotateMethod(AnnotationDescription.Builder.ofType(GET.class).build())
            .make()
            .load(
                JaxRsAnnotationsDecoratorTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    Method method = resource.getMethod("get");

    Pair<CharSequence, CharSequence> httpMethodAndRoute =
        DECORATE.getHttpMethodAndRoute(resource, method);
    assertEquals("GET", String.valueOf(httpMethodAndRoute.getLeft()));
    assertNull(httpMethodAndRoute.getRight());

    AgentSpan span = mock(AgentSpan.class);
    DECORATE.onJaxRsSpan(span, null, resource, method);
    verify(span, never()).setTag(eq(KnownTags.HTTP_ROUTE), nullable(CharSequence.class));
    verify(span, never()).setResourceName(nullable(CharSequence.class), anyByte());
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
