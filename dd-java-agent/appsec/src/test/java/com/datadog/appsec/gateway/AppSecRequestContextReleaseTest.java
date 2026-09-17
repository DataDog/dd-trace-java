package com.datadog.appsec.gateway;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that {@link AppSecRequestContext#close()} releases the request/response header maps and the
 * published derivatives map by replacing references rather than mutating them in place, so that a
 * reader on the trace-processing thread cannot observe a collection being emptied mid-iteration
 * (APPSEC-70134).
 *
 * <p>The assertions read the private fields reflectively on purpose: the invariant is about which
 * instance the context still points at, and the accessors deliberately hide that (the header
 * getters null-coalesce, and {@code getDerivativeKeys} exposes only a key view).
 */
class AppSecRequestContextReleaseTest {

  @Test
  void closeDoesNotMutateThePublishedDerivativesMap() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.reportDerivatives(singletonMap("test_attr", singletonMap("value", "test_value")));
    Map<String, Object> published = derivativesOf(ctx).get();
    assertEquals(singleton("test_attr"), published.keySet());

    ctx.close();

    // The reference is detached, but the instance a concurrent reader holds is untouched.
    assertNull(derivativesOf(ctx).get());
    assertEquals(singleton("test_attr"), published.keySet());
    assertEquals("test_value", published.get("test_attr"));
  }

  @Test
  void closeReleasesTheHeaderMapsWithoutMutatingTheReaderSnapshots() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.addRequestHeader("accept", "*");
    ctx.addResponseHeader("content-type", "text/plain");
    Map<String, List<String>> requestHeaders = ctx.getRequestHeaders();
    Map<String, List<String>> responseHeaders = ctx.getResponseHeaders();

    ctx.close();

    // Readers holding these maps (e.g. async schema extraction) still see their contents.
    assertEquals(singletonMap("accept", singletonList("*")), requestHeaders);
    assertEquals(singletonMap("content-type", singletonList("text/plain")), responseHeaders);

    // The context dropped its own reference, so the contents are collectable.
    assertNull(headersOf(ctx, "requestHeaders"));
    assertNull(headersOf(ctx, "responseHeaders"));
  }

  @Test
  void closeReleasesTheHeaderMapsWithoutAllocatingReplacements() {
    AppSecRequestContext ctx = new AppSecRequestContext();

    // An untouched context holds no header maps at all.
    assertNull(headersOf(ctx, "requestHeaders"));
    assertNull(headersOf(ctx, "responseHeaders"));

    ctx.addRequestHeader("accept", "*");
    ctx.addResponseHeader("content-type", "text/plain");
    // close() runs twice per request: onRequestEnded, then onRootSpanPublished.
    ctx.close();
    ctx.close();

    assertNull(headersOf(ctx, "requestHeaders"));
    assertNull(headersOf(ctx, "responseHeaders"));
    // Readers see the shared empty map rather than a freshly allocated one.
    assertSame(emptyMap(), ctx.getRequestHeaders());
    assertSame(emptyMap(), ctx.getResponseHeaders());
  }

  @Test
  void addressExtractionAfterCloseDoesNotRematerialiseTheHeaderMaps() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.addRequestHeader("user-agent", "foo");
    ctx.close();

    // A late WAF result extracts an attribute from a header address.
    Map<String, Object> attribute = new HashMap<>();
    attribute.put("address", "server.request.headers");
    attribute.put("key_path", singletonList("user-agent"));
    ctx.reportDerivatives(singletonMap("_dd.appsec.s.res.user_agent", attribute));

    // The address lookup read the released field instead of allocating a new map.
    assertNull(headersOf(ctx, "requestHeaders"));
    assertSame(emptyMap(), ctx.getRequestHeaders());
    assertTrue(ctx.getDerivativeKeys().isEmpty());
  }

  @Test
  void headersCanStillBeRecordedAfterClose() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.close();

    // A late header arrives after the maps were released; it is accepted, as it was when close()
    // cleared the maps in place.
    ctx.addRequestHeader("accept", "*");
    ctx.addResponseHeader("content-type", "text/plain");

    assertEquals(singletonMap("accept", singletonList("*")), ctx.getRequestHeaders());
    assertEquals(
        singletonMap("content-type", singletonList("text/plain")), ctx.getResponseHeaders());
  }

  @Test
  void reportDerivativesPublishesANewMapAndLeavesEarlierSnapshotsUntouched() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.reportDerivatives(singletonMap("attr1", singletonMap("value", "value1")));
    Map<String, Object> first = derivativesOf(ctx).get();

    ctx.reportDerivatives(singletonMap("attr2", singletonMap("value", "value2")));
    Map<String, Object> second = derivativesOf(ctx).get();

    assertNotSame(first, second);
    assertEquals(singleton("attr1"), first.keySet());
    assertEquals(new HashSet<>(asList("attr1", "attr2")), second.keySet());
  }

  @Test
  void publishedDerivativesMapRejectsMutation() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.reportDerivatives(singletonMap("test_attr", singletonMap("value", "test_value")));
    Map<String, Object> published = derivativesOf(ctx).get();

    assertThrows(UnsupportedOperationException.class, published::clear);
  }

  @ParameterizedTest(name = "hasDerivativeKeyStartingWith: {0}")
  @MethodSource("derivativePrefixScenarios")
  void hasDerivativeKeyStartingWith(
      String scenario, Map<String, Object> derivatives, boolean expected) {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.reportDerivatives(derivatives);

    assertEquals(expected, ctx.hasDerivativeKeyStartingWith("_dd.appsec.s."));
  }

  private static Stream<Arguments> derivativePrefixScenarios() {
    Map<String, Object> severalKeys = new HashMap<>();
    severalKeys.put("_dd.appsec.fp.http.header", singletonMap("value", "x"));
    severalKeys.put("_dd.appsec.s.req.body", singletonMap("value", "y"));
    return Stream.of(
        arguments("no derivatives reported", emptyMap(), false),
        arguments(
            "matching key",
            singletonMap("_dd.appsec.s.req.body", singletonMap("value", "x")),
            true),
        arguments(
            "non-matching key",
            singletonMap("_dd.appsec.fp.http.header", singletonMap("value", "x")),
            false),
        arguments("one of several matches", severalKeys, true));
  }

  @Test
  void aNullDerivativeKeyIsStoredButNeverMatches() {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.reportDerivatives(singletonMap(null, singletonMap("value", "x")));

    assertTrue(ctx.getDerivativeKeys().contains(null));
    assertFalse(ctx.hasDerivativeKeyStartingWith("_dd.appsec.s."));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> headersOf(AppSecRequestContext ctx, String fieldName) {
    return (Map<String, List<String>>) read(ctx, fieldName);
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<Map<String, Object>> derivativesOf(AppSecRequestContext ctx) {
    return (AtomicReference<Map<String, Object>>) read(ctx, "derivatives");
  }

  private static Object read(AppSecRequestContext ctx, String fieldName) {
    try {
      Field field = AppSecRequestContext.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(ctx);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot read " + fieldName, e);
    }
  }
}
