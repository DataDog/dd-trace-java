package datadog.trace.instrumentation.rabbitmq.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class RabbitDsmNamesTest {

  @TableTest({
    "Scenario                      | name                                                      | expected              ",
    "server-named queue            | amq.gen-JzTY20BRgKO-HjmUJj0wLg                            | amq.gen-*             ",
    "direct reply-to               | amq.rabbitmq.reply-to                                     | amq.rabbitmq.reply-to ",
    "direct reply-to with suffix   | amq.rabbitmq.reply-to.g1h2AA5yZXBseUAxMjcuMC4wLjE=        | amq.rabbitmq.reply-to ",
    "bus endpoint                  | ip-10-0-1-2_PaymentService_bus_ybyyyyb6tamq6nr4bdpwzyhwbo | *_PaymentService_bus_*",
    "bus endpoint upper case       | HOST01_OrderService_BUS_ABC123                            | *_OrderService_bus_*  ",
    "bus endpoint with underscores | my_host_name_Worker_bus_x1                                | *_Worker_bus_*        ",
    "wolverine control queue       | wolverine.control.3                                       | wolverine.control.*   ",
    "wolverine response queue      | wolverine.response.8f14e45f-ceea-467a-9f3a-6b1f1e2c0a11   | wolverine.response.*  ",
    "wolverine replies queue       | wolverine.replies.node-1                                  | wolverine.replies.*   ",
    "trailing dashed uuid          | callback-8f14e45f-ceea-467a-9f3a-6b1f1e2c0a11             | callback-*            ",
    "trailing upper case uuid      | callback-8F14E45F-CEEA-467A-9F3A-6B1F1E2C0A11             | callback-*            ",
    "uuid after multi-dash prefix  | reply-queue-8f14e45f-ceea-467a-9f3a-6b1f1e2c0a11          | reply-queue-*         ",
    "trailing undashed uuid        | reply_8f14e45fceea467a9f3a6b1f1e2c0a11                    | reply_*               ",
    "trailing long hex             | membership-9f8e7d6c5b4a39281706f5e4d3c2b1a0               | membership-*          ",
    "trailing 16 hex after dot     | events.0123456789abcdef                                   | events.*              ",
    "underscore and dashed uuid    | foo_8f14e45f-ceea-467a-9f3a-6b1f1e2c0a11                  | foo_*                 ",
    "bare dashed uuid              | 32f268c3-9791-4f81-b471-e1f31c1607d9                      | *                     ",
    "bare upper case dashed uuid   | 8F14E45F-CEEA-467A-9F3A-6B1F1E2C0A11                      | *                     ",
    "bare undashed uuid            | 8f14e45fceea467a9f3a6b1f1e2c0a11                          | *                     "
  })
  void normalizesPerInstanceNames(String name, String expected) {
    assertEquals(expected, RabbitDsmNames.normalize(name));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "orders",
        "orders-v2",
        "queue-abc123",
        "some-exchange",
        "amq.direct",
        "amq.gen",
        "events.0123456789abcde",
        "deadbeefdeadbeefdeadbeef",
        "8f14e45f-ceea-467a-9f3a-6b1f1e2c0a1",
        "8f14e45fceea467a9f3a6b1f1e2c0a1",
        "orders0123456789abcdef",
        "callback-8f14e45f-ceea-467a-9f3a-6b1f1e2c0a1",
        "callbackx8f14e45f-ceea-467a-9f3a-6b1f1e2c0a11",
        "PaymentService_bus_abc",
        "_x_bus_abc",
        "host_svc_bus_",
        "host_svc_bus_abc-def",
        "wolverine.control.",
        "wolverine.other.3",
        "wolverine.control"
      })
  void keepsOtherNamesUnchanged(String name) {
    assertSame(name, RabbitDsmNames.normalize(name));
  }

  @Test
  void normalizedNamesAreStable() {
    for (String name :
        new String[] {
          "amq.gen-*",
          "amq.rabbitmq.reply-to",
          "*_PaymentService_bus_*",
          "wolverine.control.*",
          "callback-*",
          "*"
        }) {
      assertEquals(name, RabbitDsmNames.normalize(name));
    }
  }
}
