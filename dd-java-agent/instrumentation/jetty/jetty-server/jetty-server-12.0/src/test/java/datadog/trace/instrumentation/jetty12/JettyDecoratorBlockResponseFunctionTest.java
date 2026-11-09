package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JettyDecorator#registerBlockResponseFunction}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version.
 */
class JettyDecoratorBlockResponseFunctionTest {

  private boolean appSecActiveBefore;
  private final List<BlockResponseFunction> registered = new ArrayList<>();

  @BeforeEach
  void saveAppSecState() {
    appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE;
  }

  @AfterEach
  void restoreAppSecState() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore;
  }

  @Test
  void registersBrfWhenAppSecActiveAndRequestContextPresent() {
    ActiveSubsystems.APPSEC_ACTIVE = true;

    DECORATE.registerBlockResponseFunction(
        span(requestContext()), fake(Request.class), fake(Response.class));

    assertEquals(1, registered.size());
    assertInstanceOf(JettyBlockResponseFunction.class, registered.get(0));
  }

  @Test
  void keepsAlreadyRegisteredBrf() {
    ActiveSubsystems.APPSEC_ACTIVE = true;
    BlockResponseFunction existing = (segment, status, type, headers, id) -> false;

    DECORATE.registerBlockResponseFunction(
        span(requestContext(existing)), fake(Request.class), fake(Response.class));

    assertEquals(0, registered.size());
  }

  @Test
  void doesNotRegisterWhenAppSecInactive() {
    ActiveSubsystems.APPSEC_ACTIVE = false;

    DECORATE.registerBlockResponseFunction(
        span(requestContext()), fake(Request.class), fake(Response.class));

    assertEquals(0, registered.size());
  }

  @Test
  void doesNotFailWithoutRequestContext() {
    ActiveSubsystems.APPSEC_ACTIVE = true;

    AgentSpan span = span(null);
    DECORATE.registerBlockResponseFunction(span, fake(Request.class), fake(Response.class));

    assertEquals(0, registered.size());
    assertNull(span.getRequestContext());
  }

  private RequestContext requestContext() {
    return requestContext(null);
  }

  private RequestContext requestContext(BlockResponseFunction existing) {
    return proxy(
        RequestContext.class,
        (name, args) -> {
          if ("setBlockResponseFunction".equals(name)) {
            registered.add((BlockResponseFunction) args[0]);
          } else if ("getBlockResponseFunction".equals(name)) {
            return existing;
          }
          return null;
        });
  }

  private static AgentSpan span(RequestContext requestContext) {
    return proxy(
        AgentSpan.class, (name, args) -> "getRequestContext".equals(name) ? requestContext : null);
  }

  private static <T> T fake(Class<T> type) {
    return proxy(type, (name, args) -> null);
  }

  private interface Handler {
    Object handle(String methodName, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Handler handler) {
    return (T)
        Proxy.newProxyInstance(
            JettyDecoratorBlockResponseFunctionTest.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "toString":
                  return type.getSimpleName() + "Fake";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  return handler.handle(method.getName(), args);
              }
            });
  }
}
