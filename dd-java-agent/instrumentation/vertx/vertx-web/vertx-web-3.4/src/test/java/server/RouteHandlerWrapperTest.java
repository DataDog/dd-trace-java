package server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

class RouteHandlerWrapperTest {

  @Test
  void updateRouteWritesRouteToBothSpans() {
    RoutingContext context = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    Route route = mock(Route.class);
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(route.getPath()).thenReturn("/items/:id");
    when(context.mountPoint()).thenReturn(null);
    when(context.request()).thenReturn(request);
    when(request.path()).thenReturn("/items/123");
    when(request.rawMethod()).thenReturn("GET");
    when(context.get("dd." + Tags.HTTP_ROUTE)).thenReturn(null);
    when(handlerSpan.getSpanName()).thenReturn("vertx.route-handler");
    when(handlerSpan.getResourceNamePriority()).thenReturn(Byte.MIN_VALUE);

    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan);

    verify(route).getPath();
    verify(context).mountPoint();
    verify(context, times(2)).request();
    verify(request).path();
    verify(request).rawMethod();
    verify(context).get("dd." + Tags.HTTP_ROUTE);
    verify(context).put("dd.vertx.matched_route", "/items/:id");
    verify(context).put("dd." + Tags.HTTP_ROUTE, "/items/:id");
    verify(parentSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(parentSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verify(handlerSpan).getSpanName();
    verify(handlerSpan).getResourceNamePriority();
    verify(handlerSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(handlerSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verifyNoMoreInteractions(context, request, route, parentSpan, handlerSpan);
  }

  @Test
  void updateRouteDoesNotWriteRouteToNonVertxHandlerSpan() {
    RoutingContext context = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    Route route = mock(Route.class);
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(route.getPath()).thenReturn("/items/:id");
    when(context.mountPoint()).thenReturn(null);
    when(context.request()).thenReturn(request);
    when(request.path()).thenReturn("/items/123");
    when(request.rawMethod()).thenReturn("GET");
    when(context.get("dd." + Tags.HTTP_ROUTE)).thenReturn(null);
    when(handlerSpan.getSpanName()).thenReturn("some.other.span");

    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan);

    verify(route).getPath();
    verify(context).mountPoint();
    verify(context, times(2)).request();
    verify(request).path();
    verify(request).rawMethod();
    verify(context).get("dd." + Tags.HTTP_ROUTE);
    verify(context).put("dd.vertx.matched_route", "/items/:id");
    verify(context).put("dd." + Tags.HTTP_ROUTE, "/items/:id");
    verify(parentSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(parentSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verify(handlerSpan).getSpanName();
    verify(handlerSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verifyNoMoreInteractions(context, request, route, parentSpan, handlerSpan);
  }

  @Test
  void updateRouteDoesNotReplaceRootRouteWhenOneExists() {
    RoutingContext context = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    Route route = mock(Route.class);
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(route.getPath()).thenReturn("/");
    when(context.mountPoint()).thenReturn(null);
    when(context.request()).thenReturn(request);
    when(request.path()).thenReturn("/");
    when(request.rawMethod()).thenReturn("GET");
    when(context.get("dd." + Tags.HTTP_ROUTE)).thenReturn(null);
    when(parentSpan.getTag(Tags.HTTP_ROUTE)).thenReturn("/existing");

    RouteUpdateHelper.updateRouteFromMatchedRoute(context, route, parentSpan, handlerSpan);

    verify(route).getPath();
    verify(context).mountPoint();
    verify(context, times(2)).request();
    verify(request).path();
    verify(request).rawMethod();
    verify(context).get("dd." + Tags.HTTP_ROUTE);
    verify(context).put("dd.vertx.matched_route", "/");
    verify(parentSpan).getTag(Tags.HTTP_ROUTE);
    verify(context, never()).put("dd." + Tags.HTTP_ROUTE, "/");
    verify(parentSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verify(handlerSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verifyNoMoreInteractions(context, request, route, parentSpan, handlerSpan);
  }
}
