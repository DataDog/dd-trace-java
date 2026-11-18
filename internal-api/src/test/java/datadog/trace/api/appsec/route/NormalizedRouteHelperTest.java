package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.NormalizedRouteHelper.NORMALIZED_ROUTE_TAG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NormalizedRouteHelperTest {

  private static final PresenceResolver THROWING =
      new PresenceResolver() {
        @Override
        public RoutePresence presenceOf(RoutePart.Param param) {
          throw new IllegalStateException("boom");
        }

        @Override
        public RoutePresence presenceOf(RoutePart.Group group) {
          throw new IllegalStateException("boom");
        }
      };

  private final AgentSpan span = mock(AgentSpan.class);

  @BeforeEach
  void spanHasRoute() {
    when(span.getTag(Tags.HTTP_ROUTE)).thenReturn("/users/{id}");
  }

  @Test
  void tagsTheSpanWhenEnabled() {
    NormalizedRouteHelper.tag(true, span, SpringRouteParser.parse("/users/{id}"), null, null);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, "/users/{id}");
  }

  @Test
  void includesTheDecodedPrefix() {
    NormalizedRouteHelper.tag(true, span, SpringRouteParser.parse("/users/{id}"), "/ctx", null);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, "/ctx/users/{id}");
  }

  @Test
  void noTagWhenDisabled() {
    NormalizedRouteHelper.tag(false, span, SpringRouteParser.parse("/users/{id}"), "/ctx", null);

    verifyNoInteractions(span);
  }

  @Test
  void noTagWithoutTemplate() {
    NormalizedRouteHelper.tag(true, span, null, "/ctx", null);

    verifyNoInteractions(span);
  }

  @Test
  void noTagWithoutHttpRoute() {
    AgentSpan noRoute = mock(AgentSpan.class);

    NormalizedRouteHelper.tag(true, noRoute, SpringRouteParser.parse("/users/{id}"), null, null);

    verify(noRoute, never()).setTag(eq(NORMALIZED_ROUTE_TAG), anyString());
  }

  @Test
  void noTagForNotExpressibleTemplate() {
    NormalizedRouteHelper.tag(true, span, RouteTemplate.NOT_EXPRESSIBLE, "/ctx", null);

    verify(span, never()).setTag(eq(NORMALIZED_ROUTE_TAG), anyString());
  }

  @Test
  void noTagForUnparseableRoute() {
    NormalizedRouteHelper.tag(true, span, SpringRouteParser.parse("/a/{b"), "/ctx", null);

    verify(span, never()).setTag(eq(NORMALIZED_ROUTE_TAG), anyString());
  }

  @Test
  void noFailureWithoutSpan() {
    assertDoesNotThrow(() ->
        NormalizedRouteHelper.tag(true, null, SpringRouteParser.parse("/users/{id}"), null, null));
  }

  @Test
  void dropsTheTagWhenRenderThrows() {
    NormalizedRouteHelper.tag(
        true, span, SpringRouteParser.parse("/files/{*path}"), null, THROWING);

    verify(span, never()).setTag(eq(NORMALIZED_ROUTE_TAG), anyString());
  }

  @Test
  void swallowsGetTagFailures() {
    when(span.getTag(Tags.HTTP_ROUTE)).thenThrow(new IllegalStateException("boom"));

    assertDoesNotThrow(() ->
        NormalizedRouteHelper.tag(true, span, SpringRouteParser.parse("/users/{id}"), null, null));
    verify(span, never()).setTag(eq(NORMALIZED_ROUTE_TAG), anyString());
  }

  @Test
  void swallowsSetTagFailures() {
    when(span.setTag(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

    NormalizedRouteHelper.tag(true, span, SpringRouteParser.parse("/users/{id}"), null, null);

    verify(span).setTag(NORMALIZED_ROUTE_TAG, "/users/{id}");
  }

  @Test
  void decodesThePrefixPath() {
    String path = new String("/ctx/a+b");
    assertSame(path, NormalizedRouteHelper.decodePath(path));
    assertEquals("/caf\u00e9", NormalizedRouteHelper.decodePath("/caf%C3%A9"));
    assertNull(NormalizedRouteHelper.decodePath(null));
  }
}
