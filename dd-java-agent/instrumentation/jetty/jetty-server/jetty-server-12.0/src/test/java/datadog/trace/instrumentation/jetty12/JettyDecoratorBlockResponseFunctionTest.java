package datadog.trace.instrumentation.jetty12;

import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.fake;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyDecorator#registerBlockResponseFunction}. */
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

  @TableTest({
    "Scenario           | AppSec Active | Request Context | Existing BRF | Registered",
    "registers          | true          | true            | false        | true      ",
    "keeps existing BRF | true          | true            | true         | false     ",
    "AppSec inactive    | false         | true            | false        | false     ",
    "no request context | true          | false           | false        | false     "
  })
  void registersBlockResponseFunction(
      boolean appSecActive, boolean requestContext, boolean existingBrf, boolean registers) {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActive;
    BlockResponseFunction existing =
        existingBrf ? (segment, status, type, headers, id) -> false : null;

    DECORATE.registerBlockResponseFunction(
        span(requestContext ? requestContext(existing) : null),
        fake(Request.class),
        fake(Response.class));

    assertEquals(registers ? 1 : 0, registered.size());
    if (registers) {
      assertInstanceOf(JettyBlockResponseFunction.class, registered.get(0));
    }
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
}
