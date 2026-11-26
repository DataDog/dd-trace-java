package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.KnownTags;
import datadog.trace.api.Pair;
import datadog.trace.api.appsec.route.JaxRsRequestMatch;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.annotation.AnnotationFormatError;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * A resource whose annotations are malformed: {@code @Path} is not repeatable, so a method that
 * carries it twice makes reading that method's annotations throw {@link AnnotationFormatError}.
 */
class JaxRsAnnotationsDecoratorTest {

  private static final ContextStore<Object, Object> REQUEST_MATCHES =
      getContextStore(
          getContextStoreId(AgentSpan.class.getName(), JaxRsRequestMatch.class.getName()));

  private final List<AgentSpan> storeKeys = new ArrayList<>();
  private boolean appSecActive;

  @BeforeEach
  void activateAppSec() {
    appSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActive;
  }

  @AfterEach
  void clearStore() {
    for (AgentSpan key : storeKeys) {
      REQUEST_MATCHES.remove(key);
    }
  }

  /** A resource with a catch-all path parameter. */
  @Path("/files")
  public static class Files {
    @GET
    @Path("{path: .*}")
    public String get() {
      return "file";
    }
  }

  /** A sub-resource locator: a route but no http method. */
  @Path("/locator")
  public static class Locator {
    @Path("{id}")
    public SubResource sub() {
      return new SubResource();
    }
  }

  /** The sub-resource {@link Locator} returns; its route alone is truncated. */
  public static class SubResource {
    @GET
    @Path("{name}")
    public String get() {
      return "sub";
    }
  }

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

  /** The route is rendered under the captured prefix, with presence from the bound values. */
  @TableTest({
    "Scenario        | Route                 | Prefix      | Name | Value | Tag                  ",
    "static route    | '/users'              | '/ctx/'     |      |       | '/ctx/users'         ",
    "no prefix       | '/users/{id}'         |             | id   | '42'  | '/users/{id}'        ",
    "regex removed   | '/users/{id: [0-9]+}' | '/ctx/api/' | id   | '42'  | '/ctx/api/users/{id}'",
    "encoded prefix  | '/users/{id}'         | '/c%20x/'   | id   | '42'  | '/c%20x/users/{id}'  ",
    "catch-all value | '/files/{path: .*}'   | '/ctx/'     | path | 'a/b' | '/ctx/files/{path}'  ",
    "catch-all empty | '/files/{path: .*}'   | '/ctx/'     | path | ''    | '/ctx/files'         ",
    "catch-all slash | '/files/{path: .*}'   | '/ctx/'     | path | '/'   | '/ctx/files/'        "
  })
  void tagsTheRouteUnderTheCapturedPrefix(
      String route, String prefix, String name, String value, String tag) {
    AgentSpan span = routedSpan();
    Map<String, String> boundValues = name == null ? null : singletonMap(name, value);

    JaxRsAnnotationsDecorator.tagNormalizedRoute(
        span, route, JaxRsRequestMatch.of(prefix, boundValues));

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, tag);
  }

  @Test
  void noTagWithoutACapturedMatch() {
    AgentSpan span = routedSpan();

    JaxRsAnnotationsDecorator.tagNormalizedRoute(span, "/users/{id}", null);

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagForAMalformedRoute() {
    AgentSpan span = routedSpan();

    JaxRsAnnotationsDecorator.tagNormalizedRoute(
        span, "/users/{id", JaxRsRequestMatch.of("/ctx/", null));

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void tagsTheRootSpanFromTheStoredMatch() throws Exception {
    AgentSpan span = routedSpan();
    store(span, JaxRsRequestMatch.of("/ctx/", singletonMap("path", "/")));

    DECORATE.onJaxRsSpan(span, null, Files.class, Files.class.getMethod("get"));

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/ctx/files/");
  }

  @Test
  void tagsTheLocalRootSpanFromTheStoredMatch() throws Exception {
    AgentSpan localRoot = routedSpan();
    when(localRoot.getLocalRootSpan()).thenReturn(localRoot);
    AgentSpan span = mock(AgentSpan.class);
    store(localRoot, JaxRsRequestMatch.of("/ctx/", singletonMap("path", "a/b")));

    DECORATE.onJaxRsSpan(span, localRoot, Files.class, Files.class.getMethod("get"));

    verify(localRoot).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/ctx/files/{path}");
    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagWithoutAStoredMatch() throws Exception {
    AgentSpan span = routedSpan();

    DECORATE.onJaxRsSpan(span, null, Files.class, Files.class.getMethod("get"));

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan span = routedSpan();
    store(span, JaxRsRequestMatch.of("/ctx/", null));

    DECORATE.onJaxRsSpan(span, null, Files.class, Files.class.getMethod("get"));

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagForASubResourceLocatorMatch() {
    AgentSpan span = routedSpan();

    JaxRsAnnotationsDecorator.tagNormalizedRoute(
        span, "/users/{id}", JaxRsRequestMatch.SUBRESOURCE);

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  /**
   * Route-based naming off keeps the resource name priority low, so the sub-resource method sets
   * http.route again after the locator; the locator's mark keeps it from setting the tag.
   */
  @Test
  void aSubResourceLocatorMarksTheLocalRootAndPreventsTheTag() throws Exception {
    AgentSpan localRoot = routedSpan();
    when(localRoot.getLocalRootSpan()).thenReturn(localRoot);
    storeKeys.add(localRoot);

    DECORATE.onJaxRsSpan(mock(AgentSpan.class), localRoot, Locator.class, locatorMethod());
    assertSame(JaxRsRequestMatch.SUBRESOURCE, REQUEST_MATCHES.get(localRoot));
    DECORATE.onJaxRsSpan(mock(AgentSpan.class), localRoot, SubResource.class, subMethod());

    verify(localRoot).setTag(eq(KnownTags.HTTP_ROUTE), route("/locator/{id}"));
    verify(localRoot).setTag(eq(KnownTags.HTTP_ROUTE), route("/{name}"));
    verify(localRoot, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void aRootSubResourceLocatorMarksItsSpan() throws Exception {
    AgentSpan span = routedSpan();
    storeKeys.add(span);

    DECORATE.onJaxRsSpan(span, null, Locator.class, locatorMethod());

    assertSame(JaxRsRequestMatch.SUBRESOURCE, REQUEST_MATCHES.get(span));
    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noMarkWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan span = routedSpan();
    storeKeys.add(span);

    DECORATE.onJaxRsSpan(span, null, Locator.class, locatorMethod());

    assertNull(REQUEST_MATCHES.get(span));
  }

  private static CharSequence route(String expected) {
    return argThat(route -> route != null && expected.equals(route.toString()));
  }

  private void store(AgentSpan localRoot, JaxRsRequestMatch match) {
    storeKeys.add(localRoot);
    REQUEST_MATCHES.put(localRoot, match);
  }

  private static Method locatorMethod() throws NoSuchMethodException {
    return Locator.class.getMethod("sub");
  }

  private static Method subMethod() throws NoSuchMethodException {
    return SubResource.class.getMethod("get");
  }

  /** A span that already carries {@code http.route}, as after {@code withRoute}. */
  private static AgentSpan routedSpan() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(HTTP_ROUTE)).thenReturn("route");
    return span;
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
