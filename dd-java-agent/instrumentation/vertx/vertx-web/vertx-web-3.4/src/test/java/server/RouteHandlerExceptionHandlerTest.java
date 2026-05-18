package server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for the vertx-web 3.x route-handler span lifecycle on the {@code
 * response.exceptionHandler} path.
 *
 * <p>{@code HttpServerResponseImpl.handleException} is invoked by Vert.x on non-{@code
 * CLOSED_EXCEPTION} I/O failures of the response. Neither {@code endHandler} nor {@code
 * bodyEndHandler} fires on this path, so the route-handler span would leak without an exception
 * handler registered. This test drives the registered handler directly and asserts the trace is
 * flushed.
 */
class RouteHandlerExceptionHandlerTest extends AbstractInstrumentationTest {

  @Test
  void exceptionHandlerFinishesRouteHandlerSpan() throws Exception {
    final Map<String, Object> store = new HashMap<>();

    RoutingContext routingContext = mock(RoutingContext.class);
    HttpServerResponse response = mock(HttpServerResponse.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    Route route = mock(Route.class);

    when(routingContext.response()).thenReturn(response);
    when(routingContext.request()).thenReturn(request);
    when(routingContext.currentRoute()).thenReturn(route);
    when(routingContext.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
    doAnswer(
            inv -> {
              store.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(routingContext)
        .put(anyString(), any());
    when(request.rawMethod()).thenReturn("GET");
    when(route.getPath()).thenReturn("/exception-path");

    Handler<RoutingContext> userHandler = ctx -> {};
    RouteHandlerWrapper wrapper = new RouteHandlerWrapper(userHandler);

    wrapper.handle(routingContext);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Handler<Throwable>> captor = ArgumentCaptor.forClass(Handler.class);
    verify(response).exceptionHandler(captor.capture());

    captor.getValue().handle(new IOException("simulated response I/O failure"));

    // Strict-mode trace writes only publish a trace when every span in it has finished.
    // If the registered exception handler did not finish the route-handler span,
    // waitForTraces would throw TimeoutException.
    writer.waitForTraces(1);
  }
}
