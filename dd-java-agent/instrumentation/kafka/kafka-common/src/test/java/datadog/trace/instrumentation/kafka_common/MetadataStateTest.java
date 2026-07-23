package datadog.trace.instrumentation.kafka_common;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

class MetadataStateTest {

  private static PendingConfig newPending() {
    return new PendingConfig("kafka_producer", "", new HashMap<>());
  }

  @Test
  void peekDoesNotConsumePendingConfig() {
    MetadataState state = new MetadataState();
    PendingConfig pending = newPending();
    state.setPendingConfig(pending);

    // Reviewer's concern: a transient failedUpdate must leave the pending config in place
    // so a later successful update can still take it and emit "connected".
    assertSame(pending, state.peekPendingConfig());
    assertSame(pending, state.peekPendingConfig());

    assertSame(pending, state.takePendingConfig());
    assertNull(state.peekPendingConfig());
  }
}
