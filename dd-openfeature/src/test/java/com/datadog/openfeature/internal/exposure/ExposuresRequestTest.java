package com.datadog.openfeature.internal.exposure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.JsonReading;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExposuresRequestTest {
  @Test
  void serializesSerialIdUnderTheIntakeWireKey() {
    assertTrue(exposureJson(340132).contains("\"serial_id\":340132"));
  }

  @Test
  void serializesSerialIdZeroRatherThanOmittingIt() {
    assertTrue(exposureJson(0).contains("\"serial_id\":0"));
  }

  @Test
  void omitsSerialIdKeyWhenAbsent() {
    assertFalse(exposureJson(null).contains("serial_id"));
  }

  @Test
  void serializesContextAndSubject() {
    final ExposureEvent event =
        new ExposureEvent(
            1234L,
            new Allocation("allocation"),
            new Flag("flag"),
            new Variant("variant"),
            new Subject(null, singletonMap("score", 42)),
            null);

    final Map<String, Object> json =
        JsonReading.readObject(
            new ExposuresRequest(singletonMap("service", "svc"), singletonList(event)).serialize());

    assertEquals(singletonMap("service", "svc"), json.get("context"));
    final Map<?, ?> exposure = (Map<?, ?>) ((java.util.List<?>) json.get("exposures")).get(0);
    assertEquals(singletonMap("attributes", singletonMap("score", 42L)), exposure.get("subject"));
  }

  private static String exposureJson(final Integer serialId) {
    final ExposureEvent event =
        new ExposureEvent(
            1234L,
            new Allocation("allocation"),
            new Flag("flag"),
            new Variant("variant"),
            new Subject("subject", emptyMap()),
            serialId);
    return new String(new ExposuresRequest(emptyMap(), singletonList(event)).serialize(), UTF_8);
  }
}
