package datadog.trace.instrumentation.undertow;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import org.junit.jupiter.api.Test;

class UndertowDecoratorTest {

  @Test
  void methodIsNullWhenRequestLineNotParsed() {
    HttpServerExchange exchange = new HttpServerExchange(null);

    assertNull(DECORATE.method(exchange));
  }

  @Test
  void methodIsReturnedWhenParsed() {
    HttpServerExchange exchange = new HttpServerExchange(null);
    exchange.setRequestMethod(Methods.POST);

    assertEquals("POST", DECORATE.method(exchange));
  }
}
