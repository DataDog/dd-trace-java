package server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteHandlerWrapperTest {

  @Test
  void updateRouteWritesRouteToBothSpans() {
    TestRoutingContext context = new TestRoutingContext("/items/123", "GET").route("/items/:id");
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(handlerSpan.getSpanName()).thenReturn("vertx.route-handler");
    when(handlerSpan.getResourceNamePriority()).thenReturn(Byte.MIN_VALUE);

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance(), context.route(), parentSpan, handlerSpan);

    context.verify("dd.vertx.matched_route", "/items/:id");
    context.verify("dd." + Tags.HTTP_ROUTE, "/items/:id");
    verify(parentSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(parentSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verify(handlerSpan).getSpanName();
    verify(handlerSpan).getResourceNamePriority();
    verify(handlerSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(handlerSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verifyNoMoreInteractions(parentSpan, handlerSpan);
  }

  @Test
  void updateRouteDoesNotWriteRouteToNonVertxHandlerSpan() {
    TestRoutingContext context = new TestRoutingContext("/items/123", "GET").route("/items/:id");
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(handlerSpan.getSpanName()).thenReturn("some.other.span");

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance(), context.route(), parentSpan, handlerSpan);

    context.verify("dd.vertx.matched_route", "/items/:id");
    context.verify("dd." + Tags.HTTP_ROUTE, "/items/:id");
    verify(parentSpan).setTag(Tags.HTTP_ROUTE, (CharSequence) "/items/:id");
    verify(parentSpan)
        .setResourceName(any(CharSequence.class), eq(ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    verify(handlerSpan).getSpanName();
    verify(handlerSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verifyNoMoreInteractions(parentSpan, handlerSpan);
  }

  @Test
  void updateRouteDoesNotReplaceRootRouteWhenOneExists() {
    TestRoutingContext context = new TestRoutingContext("/", "GET").route("/");
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);
    when(parentSpan.getTag(Tags.HTTP_ROUTE)).thenReturn("/existing");

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance(), context.route(), parentSpan, handlerSpan);

    context.verify("dd.vertx.matched_route", "/");
    context.verifyUnset("dd." + Tags.HTTP_ROUTE);
    verify(parentSpan).getTag(Tags.HTTP_ROUTE);
    verify(parentSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verify(handlerSpan, never()).setTag(any(String.class), any(CharSequence.class));
    verifyNoMoreInteractions(parentSpan, handlerSpan);
  }

  @Test
  void updateRouteIgnoresUnavailableCurrentRouteFallback() {
    TestRoutingContext context = new TestRoutingContext("/items/123", "GET");
    context.currentRouteFailure = new NullPointerException("currentRoute");
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);

    assertDoesNotThrow(
        () ->
            RouteUpdateHelper.updateRouteFromMatchedRoute(
                context.instance(), new Object(), parentSpan, handlerSpan));

    context.verifyUnset("dd." + Tags.HTTP_ROUTE);
    verifyNoMoreInteractions(parentSpan, handlerSpan);
  }

  @Test
  void updateRouteIgnoresNullCurrentRouteFallback() {
    TestRoutingContext context = new TestRoutingContext("/items/123", "GET");
    AgentSpan parentSpan = mock(AgentSpan.class);
    AgentSpan handlerSpan = mock(AgentSpan.class);

    assertDoesNotThrow(
        () ->
            RouteUpdateHelper.updateRouteFromMatchedRoute(
                context.instance(), new Object(), parentSpan, handlerSpan));

    context.verifyUnset("dd." + Tags.HTTP_ROUTE);
    verifyNoMoreInteractions(parentSpan, handlerSpan);
  }

  private static final class TestRoutingContext implements InvocationHandler {
    private final RoutingContext instance;
    private final HttpServerRequest request;
    private final Map<String, Object> data = new HashMap<>();
    private String routePath;
    private RuntimeException currentRouteFailure;

    private TestRoutingContext(String requestPath, String rawMethod) {
      this.instance = proxy(RoutingContext.class, this);
      this.request = request(requestPath, rawMethod);
    }

    private TestRoutingContext route(String routePath) {
      this.routePath = routePath;
      return this;
    }

    private RoutingContext instance() {
      return instance;
    }

    private Route route() {
      return routePath == null ? null : routeProxy(routePath);
    }

    private void verify(String key, Object value) {
      org.junit.jupiter.api.Assertions.assertEquals(value, data.get(key));
    }

    private void verifyUnset(String key) {
      org.junit.jupiter.api.Assertions.assertFalse(data.containsKey(key));
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
      switch (method.getName()) {
        case "mountPoint":
          return null;
        case "request":
          return request;
        case "currentRoute":
          if (currentRouteFailure != null) {
            throw currentRouteFailure;
          }
          return route();
        case "get":
          return data.get(args[0]);
        case "put":
          data.put((String) args[0], args[1]);
          return proxy;
        default:
          return defaultValue(method.getReturnType());
      }
    }

    private static HttpServerRequest request(String path, String rawMethod) {
      return proxy(
          HttpServerRequest.class,
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "path":
                return path;
              case "rawMethod":
                return rawMethod;
              default:
                return defaultValue(method.getReturnType());
            }
          });
    }

    private static Route routeProxy(String path) {
      return proxy(
          Route.class,
          (proxy, method, args) ->
              "getPath".equals(method.getName()) ? path : defaultValue(method.getReturnType()));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
      return type.cast(
          Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
      if (!type.isPrimitive() || Void.TYPE == type) {
        return null;
      }
      if (Boolean.TYPE == type) {
        return false;
      }
      if (Byte.TYPE == type) {
        return (byte) 0;
      }
      if (Short.TYPE == type) {
        return (short) 0;
      }
      if (Integer.TYPE == type) {
        return 0;
      }
      if (Long.TYPE == type) {
        return 0L;
      }
      if (Float.TYPE == type) {
        return 0F;
      }
      if (Double.TYPE == type) {
        return 0D;
      }
      if (Character.TYPE == type) {
        return (char) 0;
      }
      return null;
    }
  }
}
