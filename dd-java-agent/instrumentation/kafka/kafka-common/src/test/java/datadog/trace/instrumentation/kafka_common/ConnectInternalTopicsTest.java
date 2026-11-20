package datadog.trace.instrumentation.kafka_common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectInternalTopicsTest {
  private static final String[] INTERNAL_NAMES = {
    "_connect-offsets",
    "_connect_status",
    "mm2-offset-syncs.source.internal",
    "mm2-configs.source.internal",
    "mm2-offsets.target.internal",
    "mm2-status.target.internal",
    "source.checkpoints.internal",
    "heartbeats",
    "source.heartbeats"
  };

  @AfterEach
  void reset() {
    ConnectInternalTopics.reset();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "_connect-offsets",
        "_connect_status",
        "mm2-offset-syncs.source.internal",
        "mm2-configs.source.internal",
        "mm2-offsets.target.internal",
        "mm2-status.target.internal",
        "source.checkpoints.internal",
        "heartbeats",
        "source.heartbeats"
      })
  void skipsInternalNamesInAConnectWorker(String topic) {
    ConnectInternalTopics.markConnectWorker();

    assertTrue(ConnectInternalTopics.isInternal(topic));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "orders",
        "connect-offsets",
        "orders.internal",
        "orders-heartbeats",
        "heartbeats-v2",
        "source.orders"
      })
  void keepsApplicationTopicsInAConnectWorker(String topic) {
    ConnectInternalTopics.markConnectWorker();

    assertFalse(ConnectInternalTopics.isInternal(topic));
  }

  @Test
  void skipsConfiguredTopics() {
    ConnectInternalTopics.registerConfigured("connect-offsets");
    ConnectInternalTopics.registerConfigured("connect-configs");
    ConnectInternalTopics.registerConfigured("connect-status");

    assertTrue(ConnectInternalTopics.isInternal("connect-offsets"));
    assertTrue(ConnectInternalTopics.isInternal("connect-configs"));
    assertTrue(ConnectInternalTopics.isInternal("connect-status"));
    assertFalse(ConnectInternalTopics.isInternal("orders"));
  }

  @Test
  void ignoresEmptyConfiguredTopics() {
    ConnectInternalTopics.registerConfigured(null);
    ConnectInternalTopics.registerConfigured("");

    assertFalse(ConnectInternalTopics.isInternal(""));
    assertFalse(ConnectInternalTopics.isInternal(null));
  }

  @Test
  void keepsEveryTopicOutsideAConnectWorker() {
    for (String topic : INTERNAL_NAMES) {
      assertFalse(ConnectInternalTopics.isInternal(topic), topic);
    }
  }
}
