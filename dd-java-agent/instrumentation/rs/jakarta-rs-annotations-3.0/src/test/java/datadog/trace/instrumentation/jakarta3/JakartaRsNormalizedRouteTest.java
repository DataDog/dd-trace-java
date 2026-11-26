package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.KnownTags;
import datadog.trace.api.appsec.route.JaxRsRequestMatch;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * {@code _dd.appsec.normalized_route} set next to each {@code http.route} of {@link
 * JakartaRsAnnotationsDecorator}, from the request match a capture advice stored for the local root
 * span.
 */
class JakartaRsNormalizedRouteTest {

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

  /** The route is rendered under the captured prefix, with presence from the bound values. */
  @TableTest({
    "Scenario        | Route                 | Prefix      | Name | Value | Tag                  ",
    "static route    | '/users'              | '/ctx/'     |      |       | '/ctx/users'         ",
    "regex removed   | '/users/{id: [0-9]+}' | '/ctx/api/' | id   | '42'  | '/ctx/api/users/{id}'",
    "catch-all value | '/files/{path: .*}'   | '/ctx/'     | path | 'a/b' | '/ctx/files/{path}'  ",
    "catch-all empty | '/files/{path: .*}'   | '/ctx/'     | path | ''    | '/ctx/files'         ",
    "catch-all slash | '/files/{path: .*}'   |             | path | '/'   | '/files/'            "
  })
  void tagsTheRouteUnderTheCapturedPrefix(
      String route, String prefix, String name, String value, String tag) {
    AgentSpan span = routedSpan();

    JakartaRsAnnotationsDecorator.tagNormalizedRoute(
        span, route, JaxRsRequestMatch.of(prefix, name == null ? null : singletonMap(name, value)));

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, tag);
  }

  @Test
  void noTagWithoutACapturedMatch() {
    AgentSpan span = routedSpan();

    JakartaRsAnnotationsDecorator.tagNormalizedRoute(span, "/users/{id}", null);

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void tagsTheRootSpan() throws Exception {
    AgentSpan span = routedSpan();
    store(span, "/");

    DECORATE.onJakartaRsSpan(span, null, Files.class, get());

    verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/ctx/files/");
  }

  @Test
  void tagsTheParentWhenItIsTheLocalRoot() throws Exception {
    AgentSpan parent = routedSpan();
    when(parent.getLocalRootSpan()).thenReturn(parent);
    AgentSpan span = mock(AgentSpan.class);
    store(parent, "a/b");

    DECORATE.onJakartaRsSpan(span, parent, Files.class, get());

    verify(parent).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/ctx/files/{path}");
    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void tagsTheLocalRootWhenItRefinesACatchAllRoute() throws Exception {
    AgentSpan localRoot = routedSpan();
    when(localRoot.getTag(HTTP_ROUTE)).thenReturn("/");
    AgentSpan parent = mock(AgentSpan.class);
    when(parent.getLocalRootSpan()).thenReturn(localRoot);
    when(parent.getTag(SPAN_KIND)).thenReturn(SPAN_KIND_SERVER);
    AgentSpan span = mock(AgentSpan.class);
    store(localRoot, "");

    DECORATE.onJakartaRsSpan(span, parent, Files.class, get());

    verify(localRoot).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/ctx/files");
    verify(parent, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagWithoutAStoredMatch() throws Exception {
    AgentSpan span = routedSpan();

    DECORATE.onJakartaRsSpan(span, null, Files.class, get());

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagWhenAppSecIsInactive() throws Exception {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan span = routedSpan();
    store(span, "/");

    DECORATE.onJakartaRsSpan(span, null, Files.class, get());

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void noTagForASubResourceLocatorMatch() {
    AgentSpan span = routedSpan();

    JakartaRsAnnotationsDecorator.tagNormalizedRoute(
        span, "/users/{id}", JaxRsRequestMatch.SUBRESOURCE);

    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  /**
   * The sub-resource method overwrites the locator's http.route on a parent that is the local root;
   * the locator's mark keeps it from setting the tag.
   */
  @Test
  void aSubResourceLocatorMarksTheLocalRootAndPreventsTheTag() throws Exception {
    AgentSpan parent = routedSpan();
    when(parent.getLocalRootSpan()).thenReturn(parent);
    storeKeys.add(parent);

    DECORATE.onJakartaRsSpan(mock(AgentSpan.class), parent, Locator.class, locatorMethod());
    assertSame(JaxRsRequestMatch.SUBRESOURCE, REQUEST_MATCHES.get(parent));
    DECORATE.onJakartaRsSpan(mock(AgentSpan.class), parent, SubResource.class, subMethod());

    verify(parent).setTag(eq(KnownTags.HTTP_ROUTE), route("/locator/{id}"));
    verify(parent).setTag(eq(KnownTags.HTTP_ROUTE), route("/{name}"));
    verify(parent, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  @Test
  void aRootSubResourceLocatorMarksItsSpan() throws Exception {
    AgentSpan span = routedSpan();
    storeKeys.add(span);

    DECORATE.onJakartaRsSpan(span, null, Locator.class, locatorMethod());

    assertSame(JaxRsRequestMatch.SUBRESOURCE, REQUEST_MATCHES.get(span));
    verify(span, never()).setTag(eq(DD_APPSEC_NORMALIZED_ROUTE), anyString());
  }

  private static CharSequence route(String expected) {
    return argThat(route -> route != null && expected.equals(route.toString()));
  }

  private void store(AgentSpan localRoot, String path) {
    storeKeys.add(localRoot);
    REQUEST_MATCHES.put(localRoot, JaxRsRequestMatch.of("/ctx/", singletonMap("path", path)));
  }

  private static Method locatorMethod() throws NoSuchMethodException {
    return Locator.class.getMethod("sub");
  }

  private static Method subMethod() throws NoSuchMethodException {
    return SubResource.class.getMethod("get");
  }

  private static Method get() throws NoSuchMethodException {
    return Files.class.getMethod("get");
  }

  /** A span that already carries {@code http.route}, as after {@code withRoute}. */
  private static AgentSpan routedSpan() {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(HTTP_ROUTE)).thenReturn("route");
    return span;
  }
}
