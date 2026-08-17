package com.datadog.featureflag;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExposurePayloadsTest {

  private static final Map<String, String> CONTEXT = singletonMap("service", "test-service");

  @Test
  void splitsPayloadsAtByteLimit() throws Exception {
    final ExposureEvent first = exposure("first", repeat('a', 128));
    final ExposureEvent second = exposure("second", repeat('b', 128));
    final ExposureEvent third = exposure("third", repeat('c', 128));
    final int oneEventBytes =
        ExposurePayloads.buildPayloadsForTest(singletonList(first), CONTEXT, Integer.MAX_VALUE)
            .payloads
            .get(0)
            .body
            .length;
    final int payloadLimit = oneEventBytes * 2;

    final ExposurePayloads.EncodedPayloads encoded =
        ExposurePayloads.buildPayloadsForTest(
            Arrays.asList(first, second, third), CONTEXT, payloadLimit);

    assertEquals(2, encoded.payloads.size());
    assertEquals(0, encoded.droppedPayloadLimit);
    assertEquals(3, encoded.payloads.stream().mapToInt(payload -> payload.eventCount).sum());
    for (ExposurePayloads.EncodedPayload payload : encoded.payloads) {
      assertTrue(payload.body.length <= payloadLimit);
      assertEquals(payload.eventCount, decode(payload.body).exposures.size());
    }
  }

  @Test
  void dropsAnEventThatCannotFitInOnePayload() {
    final ExposurePayloads.EncodedPayloads encoded =
        ExposurePayloads.buildPayloadsForTest(
            singletonList(exposure("large", repeat('x', 1_024))), CONTEXT, 256);

    assertTrue(encoded.payloads.isEmpty());
    assertEquals(1, encoded.droppedPayloadLimit);
  }

  @Test
  void dropsOnlyTheEventThatCannotBeSerialized() {
    final ExposureEvent invalid =
        new ExposureEvent(
            1,
            new Allocation("allocation-invalid"),
            new Flag("flag-invalid"),
            new Variant("variant-invalid"),
            new Subject("subject-invalid", singletonMap("not-a-number", (Object) Double.NaN)));
    final ExposureEvent valid = exposure("valid", "value");

    final ExposurePayloads.EncodedPayloads encoded =
        ExposurePayloads.buildPayloadsForTest(
            Arrays.asList(invalid, valid), CONTEXT, Integer.MAX_VALUE);

    assertEquals(1, encoded.droppedSerialization);
    assertEquals(1, encoded.payloads.size());
    assertEquals(1, encoded.payloads.get(0).eventCount);
  }

  private static ExposuresRequest decode(final byte[] body) throws Exception {
    return new Moshi.Builder()
        .build()
        .adapter(ExposuresRequest.class)
        .fromJson(new String(body, StandardCharsets.UTF_8));
  }

  private static ExposureEvent exposure(final String id, final String value) {
    return new ExposureEvent(
        1,
        new Allocation("allocation-" + id),
        new Flag("flag-" + id),
        new Variant("variant-" + id),
        new Subject("subject-" + id, singletonMap("attribute", (Object) value)));
  }

  private static String repeat(final char value, final int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }
}
