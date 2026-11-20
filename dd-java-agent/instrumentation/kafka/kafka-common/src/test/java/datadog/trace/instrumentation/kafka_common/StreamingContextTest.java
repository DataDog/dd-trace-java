package datadog.trace.instrumentation.kafka_common;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class StreamingContextTest {

  @Test
  void onlyTopicsInternalToTheTopologyAreInternal() {
    StreamingContext context = new StreamingContext();
    assertFalse(context.isInternalTopic("source"), "no topology registered yet");

    // sub-topology 1 reads source and writes through, sub-topology 2 reads through and writes sink
    context.registerTopics(
        new HashSet<>(Arrays.asList("source", "through")),
        new HashSet<>(Arrays.asList("through", "sink")),
        singleton("app-store-changelog"));

    assertFalse(context.isInternalTopic("source"));
    assertFalse(context.isInternalTopic("sink"));
    assertFalse(context.isInternalTopic("unrelated"));
    assertTrue(context.isInternalTopic("through"));
    assertTrue(context.isInternalTopic("app-store-changelog"));

    context.registerTopics(emptySet(), emptySet(), singleton("app-repartition"));
    assertTrue(context.isInternalTopic("app-repartition"));
    assertTrue(context.isInternalTopic("through"));
  }
}
