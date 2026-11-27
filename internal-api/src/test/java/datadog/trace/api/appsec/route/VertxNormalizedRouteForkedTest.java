package datadog.trace.api.appsec.route;

import static datadog.trace.api.KnownTags.DD_APPSEC_NORMALIZED_ROUTE;
import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static java.util.Collections.singletonMap;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.tabletest.junit.TableTest;

/** Mutates the global {@link ActiveSubsystems#APPSEC_ACTIVE}, so it runs in its own JVM. */
class VertxNormalizedRouteForkedTest {

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
        span, "/catch-all/*", Grammar.DEFAULT, "/sub/", "/sub/catch-all/a", singletonMap("*", "a"));

    InOrder order = inOrder(span);
    order.verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    order.verify(span).setTag(DD_APPSEC_NORMALIZED_ROUTE, "/sub/catch-all/{param1}");
  }

  @TableTest({
    "Scenario               | Declared     | Mount        | Path                | Params                 ",
    "null declared path     |              |              | '/a'                | [:]                    ",
    "regex-like route       | '/a/:id?'    |              | '/a/1'              | [:]                    ",
    "param mount            | '/users/:id' | '/acme/api/' | '/acme/api/users/1' | [tenant: acme, id: '1']",
    "mount inside a segment | '/*'         | '/sub'       | '/subway/x'         | [:]                    "
  })
  void onlyClearsWhenThereIsNoTag(
      String declared, String mount, String path, Map<String, String> params) {
    AgentSpan span = spanWithRoute("/route");

    VertxNormalizedRoute.tag(span, declared, Grammar.DEFAULT, mount, path, params);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, (String) null);
    verify(span, never()).setTag(anyLong(), anyString());
  }

  private static AgentSpan spanWithRoute(String route) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getTag(Tags.HTTP_ROUTE)).thenReturn(route);
    return span;
  }
}
