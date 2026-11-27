package com.datadog.profiling.remoteconfig;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApmProfilingPayloadTest {
  @Test
  void readsTheMembersOfAnObject() {
    List<String> problems = new ArrayList<>();

    Map<String, Object> payload =
        ApmProfilingPayload.read("{\"profilers\":{\"cpuTime\":true}}", problems);

    assertTrue(payload.containsKey("profilers"), String.valueOf(payload));
    assertTrue(problems.isEmpty(), String.valueOf(problems));
  }

  // the JSON mapper takes null, empty and "null" for an empty object
  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "null",
        "{\"profilers\":{",
        "not json",
        // a profiling config followed by other content is not a single object
        "{\"profilers\":{\"cpuTime\":true}} x",
        "{\"profilers\":{\"cpuTime\":true}}{\"profilers\":{}}"
      })
  void payloadThatIsNotAnObjectIsReported(String json) {
    List<String> problems = new ArrayList<>();

    assertNull(ApmProfilingPayload.read(json, problems));
    assertEquals(singletonList("payload is not an object"), problems);
  }
}
