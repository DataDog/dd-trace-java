package datadog.trace.api.appsec.route;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Mutates the global {@link ActiveSubsystems#APPSEC_ACTIVE}, so it runs in its own JVM. */
class VertxNormalizedRouteForkedTest {

  private static final Map<String, String> NO_PARAMS = Collections.emptyMap();

  private boolean previousAppSecActive;

  @BeforeEach
  void activateAppSec() {
    previousAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
  }

  @AfterEach
  void restoreAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = previousAppSecActive;
  }

  @Test
  void clearsThePreviousTagThenSetsTheNewOne() {
    AgentSpan span = spanWithRoute("/sub/catch-all/");

    VertxNormalizedRoute.tag(
        span,
        "/catch-all/*",
        Grammar.DEFAULT,
        "/sub/",
        "/sub/catch-all/a",
        Collections.singletonMap("*", "a"));

    InOrder order = inOrder(span);
    order.verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    order.verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/sub/catch-all/{param1}");
  }

  @Test
  void onlyClearsForANullDeclaredPath() {
    AgentSpan span = spanWithRoute("/a");

    VertxNormalizedRoute.tag(span, null, Grammar.DEFAULT, null, "/a", NO_PARAMS);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    verify(span, never()).setTag(anyLong(), anyString());
  }

  @Test
  void onlyClearsForARegexLikeRoute() {
    AgentSpan span = spanWithRoute("/a/:id?");

    VertxNormalizedRoute.tag(span, "/a/:id?", Grammar.DEFAULT, null, "/a/1", NO_PARAMS);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    verify(span, never()).setTag(anyLong(), anyString());
  }

  @Test
  void onlyClearsUnderAParamMount() {
    AgentSpan span = spanWithRoute("/acme/api/users/:id");
    Map<String, String> params = new HashMap<>();
    params.put("tenant", "acme");
    params.put("id", "1");

    VertxNormalizedRoute.tag(
        span, "/users/:id", Grammar.DEFAULT, "/acme/api/", "/acme/api/users/1", params);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    verify(span, never()).setTag(anyLong(), anyString());
  }

  private static AgentSpan spanWithRoute(String route) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(Tags.HTTP_ROUTE)).thenReturn(route);
    return span;
  }
}
