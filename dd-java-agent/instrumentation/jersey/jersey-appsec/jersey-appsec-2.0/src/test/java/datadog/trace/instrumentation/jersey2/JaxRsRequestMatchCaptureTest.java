package datadog.trace.instrumentation.jersey2;

import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.appsec.route.JaxRsRequestMatch;
import datadog.trace.api.appsec.route.JaxRsRouteParser;
import datadog.trace.api.appsec.route.RouteTemplate;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JaxRsRequestMatchCaptureTest {

  private static final ContextStore<Object, Object> REQUEST_MATCHES =
      getContextStore(
          getContextStoreId(AgentSpan.class.getName(), JaxRsRequestMatch.class.getName()));

  private static final URI BASE_URI = URI.create("http://localhost:8080/c%20x/api/");
  private static final Map<String, ?> BOUND_VALUES = singletonMap("path", singletonList("a/b"));

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

  @Test
  void storesTheDecodedPrefixAndTheBoundValues() {
    AgentSpan localRoot = localRoot();

    JaxRsRequestMatchCapture.capture(spanUnder(localRoot), BASE_URI, BOUND_VALUES);

    JaxRsRequestMatch match = (JaxRsRequestMatch) REQUEST_MATCHES.get(localRoot);
    assertEquals("/c x/api/", match.prefix());
    assertEquals("/c%20x/api/files/{path}", render("/files/{path: .*}", match));
  }

  @Test
  void anEmptyRawPathStoresARootMatch() {
    AgentSpan localRoot = localRoot();

    JaxRsRequestMatchCapture.capture(
        spanUnder(localRoot), URI.create("http://localhost:8080"), singletonMap("path", ""));

    JaxRsRequestMatch match = (JaxRsRequestMatch) REQUEST_MATCHES.get(localRoot);
    assertEquals("", match.prefix());
    assertEquals("/files", render("/files/{path: .*}", match));
  }

  @Test
  void aNullBaseUriStoresNothing() {
    AgentSpan localRoot = localRoot();

    JaxRsRequestMatchCapture.capture(spanUnder(localRoot), null, BOUND_VALUES);

    assertNull(REQUEST_MATCHES.get(localRoot));
  }

  @Test
  void aNullRawPathStoresNothing() {
    AgentSpan localRoot = localRoot();

    // an opaque URI has no path
    JaxRsRequestMatchCapture.capture(
        spanUnder(localRoot), URI.create("mailto:a@example.com"), BOUND_VALUES);

    assertNull(REQUEST_MATCHES.get(localRoot));
  }

  @Test
  void noActiveSpanStoresNothing() {
    assertDoesNotThrow(() -> JaxRsRequestMatchCapture.capture(null, BASE_URI, BOUND_VALUES));
  }

  @Test
  void noLocalRootStoresNothing() {
    AgentSpan span = mock(AgentSpan.class);
    storeKeys.add(span);

    JaxRsRequestMatchCapture.capture(span, BASE_URI, BOUND_VALUES);

    assertNull(REQUEST_MATCHES.get(span));
  }

  @Test
  void keepsASubResourceMark() {
    AgentSpan localRoot = localRoot();
    REQUEST_MATCHES.put(localRoot, JaxRsRequestMatch.SUBRESOURCE);

    JaxRsRequestMatchCapture.capture(spanUnder(localRoot), BASE_URI, BOUND_VALUES);

    assertSame(JaxRsRequestMatch.SUBRESOURCE, REQUEST_MATCHES.get(localRoot));
  }

  @Test
  void storesNothingWhenAppSecIsInactive() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
    AgentSpan localRoot = localRoot();

    JaxRsRequestMatchCapture.capture(spanUnder(localRoot), BASE_URI, BOUND_VALUES);

    assertNull(REQUEST_MATCHES.get(localRoot));
  }

  private AgentSpan localRoot() {
    AgentSpan localRoot = mock(AgentSpan.class);
    storeKeys.add(localRoot);
    return localRoot;
  }

  private static AgentSpan spanUnder(AgentSpan localRoot) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getLocalRootSpan()).thenReturn(localRoot);
    return span;
  }

  private static String render(String route, JaxRsRequestMatch match) {
    RouteTemplate template = JaxRsRouteParser.cached(route);
    return template.render(match.prefix(), match.presenceFor(template));
  }
}
