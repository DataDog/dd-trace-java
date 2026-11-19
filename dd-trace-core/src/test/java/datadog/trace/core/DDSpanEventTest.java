package datadog.trace.core;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DDSpanEventTest {
  @Test
  void toStringDescribesTheEvent() {
    DDSpanEvent event = new DDSpanEvent("event", 1234L, singletonMap("key", "value"));

    assertEquals(
        "DDSpanEvent{name='event', timeUnixNano=1234, attributes={key=value}}", event.toString());
  }
}
