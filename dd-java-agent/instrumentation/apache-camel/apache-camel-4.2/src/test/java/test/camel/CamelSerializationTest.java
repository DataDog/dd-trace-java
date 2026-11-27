package test.camel;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultExchangeHolder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CamelSerializationTest extends AbstractCamelTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void serializesApplicationDataWithoutRouteState(boolean allowSerializedValues) throws Exception {
    try (DefaultCamelContext context = new DefaultCamelContext()) {
      context.addRoutes(
          new RouteBuilder() {
            @Override
            public void configure() {
              from("direct:serialize")
                  .routeId("serialize")
                  .process(exchange -> {
                    assertNotNull(activeSpan());
                    assertNotNull(
                        routeContext(exchange),
                        "serialization must run while instrumentation state is present");

                    DefaultExchangeHolder holder =
                        DefaultExchangeHolder.marshal(exchange, true, allowSerializedValues);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                      output.writeObject(holder);
                    }

                    DefaultExchangeHolder restoredHolder;
                    try (ObjectInputStream input =
                        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                      restoredHolder = (DefaultExchangeHolder) input.readObject();
                    }
                    Exchange restored = new DefaultExchange(context);
                    DefaultExchangeHolder.unmarshal(restored, restoredHolder);

                    assertEquals("payload", restored.getMessage().getBody());
                    assertEquals("header-value", restored.getMessage().getHeader("business"));
                    assertEquals("property-value", restored.getProperty("business"));
                    assertNull(
                        routeContext(restored),
                        "agent state must not cross a serialized exchange boundary");
                    assertNotNull(
                        routeContext(exchange),
                        "serialization must not remove the live route state");
                  });
            }
          });
      context.start();

      try (ProducerTemplate producer = context.createProducerTemplate()) {
        Exchange exchange = new DefaultExchange(context);
        exchange.getMessage().setBody("payload");
        exchange.getMessage().setHeader("business", "header-value");
        exchange.setProperty("business", "property-value");

        producer.send("direct:serialize", exchange);

        assertNull(exchange.getException());
        assertNull(routeContext(exchange), "completion must remove the live route state");
      }
    }

    assertEquals(1, routeSpans(1).size());
  }
}
