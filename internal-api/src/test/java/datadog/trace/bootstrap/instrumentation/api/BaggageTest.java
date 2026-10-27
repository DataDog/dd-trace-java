package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BaggageTest {

  private static final String HEADER = "key1=value1,key2=value2";

  @Test
  void testEmptyBaggage() {
    Baggage baggage = Baggage.empty();

    assertTrue(baggage.asMap().isEmpty());
    assertNotNull(baggage.getW3cHeader());
    assertTrue(baggage.getW3cHeader().isEmpty());

    baggage.addItem("key", "value");

    assertNull(baggage.getW3cHeader());
  }

  @Test
  void testBaggageCreation() {
    Map<String, String> items = items();

    Baggage baggage = Baggage.create(items);

    assertEquals(items, baggage.asMap());
    assertNull(baggage.getW3cHeader());

    baggage = Baggage.create(items, HEADER);

    assertEquals(items, baggage.asMap());
    assertEquals(HEADER, baggage.getW3cHeader());
  }

  @Test
  void testBaggageHeader() {
    Baggage baggage = Baggage.create(items(), HEADER);

    baggage.removeItem("missingKey");

    // header is preserved
    assertEquals(HEADER, baggage.getW3cHeader());

    baggage.removeItem("key2");

    // header is out of sync
    assertNull(baggage.getW3cHeader());

    baggage.setW3cHeader("key1=value1");

    // header is forced
    assertEquals("key1=value1", baggage.getW3cHeader());

    baggage.addItem("key3", "value3");

    // header is out of sync
    assertNull(baggage.getW3cHeader());
  }

  @Test
  void testContextStorage() {
    Baggage baggage = Baggage.empty();
    Context context = Context.root();

    assertNull(Baggage.fromContext(context));

    context = context.with(baggage);

    assertSame(baggage, Baggage.fromContext(context));
  }

  private static Map<String, String> items() {
    Map<String, String> items = new HashMap<>();
    items.put("key1", "value1");
    items.put("key2", "value2");
    return items;
  }
}
