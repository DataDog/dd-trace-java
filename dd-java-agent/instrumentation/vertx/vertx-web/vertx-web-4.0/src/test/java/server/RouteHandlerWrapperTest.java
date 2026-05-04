package server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.instrumentation.vertx_4_0.server.RouteUpdateHelper;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteHandlerWrapperTest {

  @Test
  void updateRouteWritesRouteToBothSpans() {
    RecordingProxy<RoutingContext> context = RecordingProxy.of(RoutingContext.class);
    RecordingProxy<HttpServerRequest> request = RecordingProxy.of(HttpServerRequest.class);
    RecordingProxy<Route> route = RecordingProxy.of(Route.class);
    RecordingProxy<AgentSpan> parentSpan = RecordingProxy.of(AgentSpan.class);
    RecordingProxy<AgentSpan> handlerSpan = RecordingProxy.of(AgentSpan.class);
    route.returns("getPath", "/items/:id");
    context.returns("mountPoint", null);
    context.returns("request", request.instance);
    request.returns("path", "/items/123");
    request.returns("method", HttpMethod.GET);
    context.returns("get", null);
    handlerSpan.returns("getSpanName", "vertx.route-handler");
    handlerSpan.returns("getResourceNamePriority", Byte.MIN_VALUE);

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance, route.instance, parentSpan.instance, handlerSpan.instance);

    assertEquals(1, route.count("getPath"));
    assertEquals(1, context.count("mountPoint"));
    assertEquals(2, context.count("request"));
    assertEquals(1, request.count("path"));
    assertEquals(1, request.count("method"));
    assertEquals(1, context.count("get", "dd." + Tags.HTTP_ROUTE));
    assertEquals(1, context.count("put", "dd.vertx.matched_route", "/items/:id"));
    assertEquals(1, context.count("put", "dd." + Tags.HTTP_ROUTE, "/items/:id"));
    assertEquals(1, parentSpan.count("setTag", Tags.HTTP_ROUTE, "/items/:id"));
    assertEquals(
        1,
        parentSpan.count(
            "setResourceName", "GET /items/:id", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    assertEquals(1, handlerSpan.count("getSpanName"));
    assertEquals(1, handlerSpan.count("getResourceNamePriority"));
    assertEquals(1, handlerSpan.count("setTag", Tags.HTTP_ROUTE, "/items/:id"));
    assertEquals(
        1,
        handlerSpan.count(
            "setResourceName", "GET /items/:id", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
  }

  @Test
  void updateRouteDoesNotWriteRouteToNonVertxHandlerSpan() {
    RecordingProxy<RoutingContext> context = RecordingProxy.of(RoutingContext.class);
    RecordingProxy<HttpServerRequest> request = RecordingProxy.of(HttpServerRequest.class);
    RecordingProxy<Route> route = RecordingProxy.of(Route.class);
    RecordingProxy<AgentSpan> parentSpan = RecordingProxy.of(AgentSpan.class);
    RecordingProxy<AgentSpan> handlerSpan = RecordingProxy.of(AgentSpan.class);
    route.returns("getPath", "/items/:id");
    context.returns("mountPoint", null);
    context.returns("request", request.instance);
    request.returns("path", "/items/123");
    request.returns("method", HttpMethod.GET);
    context.returns("get", null);
    handlerSpan.returns("getSpanName", "some.other.span");

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance, route.instance, parentSpan.instance, handlerSpan.instance);

    assertEquals(1, route.count("getPath"));
    assertEquals(1, context.count("mountPoint"));
    assertEquals(2, context.count("request"));
    assertEquals(1, request.count("path"));
    assertEquals(1, request.count("method"));
    assertEquals(1, context.count("get", "dd." + Tags.HTTP_ROUTE));
    assertEquals(1, context.count("put", "dd.vertx.matched_route", "/items/:id"));
    assertEquals(1, context.count("put", "dd." + Tags.HTTP_ROUTE, "/items/:id"));
    assertEquals(1, parentSpan.count("setTag", Tags.HTTP_ROUTE, "/items/:id"));
    assertEquals(
        1,
        parentSpan.count(
            "setResourceName", "GET /items/:id", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE));
    assertEquals(1, handlerSpan.count("getSpanName"));
    assertEquals(0, handlerSpan.count("setTag"));
    assertEquals(0, handlerSpan.count("setResourceName"));
  }

  @Test
  void updateRouteDoesNotReplaceRootRouteWhenOneExists() {
    RecordingProxy<RoutingContext> context = RecordingProxy.of(RoutingContext.class);
    RecordingProxy<HttpServerRequest> request = RecordingProxy.of(HttpServerRequest.class);
    RecordingProxy<Route> route = RecordingProxy.of(Route.class);
    RecordingProxy<AgentSpan> parentSpan = RecordingProxy.of(AgentSpan.class);
    RecordingProxy<AgentSpan> handlerSpan = RecordingProxy.of(AgentSpan.class);
    route.returns("getPath", "/");
    context.returns("mountPoint", null);
    context.returns("request", request.instance);
    request.returns("path", "/");
    request.returns("method", HttpMethod.GET);
    context.returns("get", null);
    parentSpan.returns("getTag", "/existing");

    RouteUpdateHelper.updateRouteFromMatchedRoute(
        context.instance, route.instance, parentSpan.instance, handlerSpan.instance);

    assertEquals(1, route.count("getPath"));
    assertEquals(1, context.count("mountPoint"));
    assertEquals(2, context.count("request"));
    assertEquals(1, request.count("path"));
    assertEquals(1, request.count("method"));
    assertEquals(1, context.count("get", "dd." + Tags.HTTP_ROUTE));
    assertEquals(1, context.count("put", "dd.vertx.matched_route", "/"));
    assertEquals(1, parentSpan.count("getTag", Tags.HTTP_ROUTE));
    assertEquals(0, context.count("put", "dd." + Tags.HTTP_ROUTE, "/"));
    assertEquals(0, parentSpan.count("setTag"));
    assertEquals(0, handlerSpan.count("setTag"));
  }

  private static final class RecordingProxy<T> implements InvocationHandler {
    private final Map<String, Object> returnValues = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final T instance;

    private RecordingProxy(Class<T> type) {
      this.instance =
          type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
    }

    static <T> RecordingProxy<T> of(Class<T> type) {
      return new RecordingProxy<T>(type);
    }

    void returns(String method, Object value) {
      returnValues.put(method, value);
    }

    int count(String method, Object... args) {
      int count = 0;
      for (Call call : calls) {
        if (call.matches(method, args)) {
          count++;
        }
      }
      return count;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if (method.getDeclaringClass() == Object.class) {
        return invokeObjectMethod(proxy, method, args);
      }
      Object[] arguments = args == null ? new Object[0] : args;
      calls.add(new Call(method.getName(), arguments));
      if (returnValues.containsKey(method.getName())) {
        return returnValues.get(method.getName());
      }
      Class<?> returnType = method.getReturnType();
      if (returnType.isInstance(proxy)) {
        return proxy;
      }
      return defaultValue(returnType);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
      if ("toString".equals(method.getName())) {
        return proxy.getClass().getInterfaces()[0].getName() + " proxy";
      }
      if ("hashCode".equals(method.getName())) {
        return System.identityHashCode(proxy);
      }
      if ("equals".equals(method.getName())) {
        return proxy == args[0];
      }
      throw new UnsupportedOperationException(method.getName());
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

  private static final class Call {
    private final String method;
    private final Object[] args;

    private Call(String method, Object[] args) {
      this.method = method;
      this.args = args;
    }

    private boolean matches(String method, Object[] args) {
      if (!this.method.equals(method) || this.args.length != args.length) {
        return false;
      }
      for (int i = 0; i < args.length; i++) {
        if (!argumentMatches(this.args[i], args[i])) {
          return false;
        }
      }
      return true;
    }

    private static boolean argumentMatches(Object actual, Object expected) {
      if (actual instanceof CharSequence && expected instanceof CharSequence) {
        return actual.toString().contentEquals((CharSequence) expected);
      }
      return java.util.Objects.equals(actual, expected);
    }
  }
}
