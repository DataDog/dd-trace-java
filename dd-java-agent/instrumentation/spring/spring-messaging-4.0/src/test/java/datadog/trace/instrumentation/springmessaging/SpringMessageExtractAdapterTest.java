package datadog.trace.instrumentation.springmessaging;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.GenericMessage;

class SpringMessageExtractAdapterTest {

  @Test
  void ignoresNullMessage() {
    List<String> seen = new ArrayList<>();

    SpringMessageExtractAdapter.GETTER.forEachKey(null, collectInto(seen));

    assertEquals(emptyList(), seen);
  }

  @Test
  void ignoresMessageWithoutHeaders() {
    List<String> seen = new ArrayList<>();

    SpringMessageExtractAdapter.GETTER.forEachKey(
        new GenericMessage<>("payload", new HashMap<String, Object>()), collectInto(seen));

    assertEquals(emptyList(), seen);
  }

  @Test
  void normalizesKeysAndSkipsNonStringHeaders() {
    Map<String, Object> headers = new HashMap<>();
    headers.put("X-Datadog-Trace-Id", "123");
    headers.put("AWSTraceHeader", "Root=1-abc");
    headers.put("not-a-string", 42);
    List<String> seen = new ArrayList<>();

    SpringMessageExtractAdapter.GETTER.forEachKey(
        new GenericMessage<>("payload", headers), collectInto(seen));

    // header order is not guaranteed, so compare as sorted entries
    seen.sort(String::compareTo);
    assertEquals(asList("x-amzn-trace-id=Root=1-abc", "x-datadog-trace-id=123"), seen);
  }

  private static AgentPropagation.KeyClassifier collectInto(List<String> seen) {
    return (key, value) -> {
      seen.add(key + "=" + value);
      return true;
    };
  }
}
