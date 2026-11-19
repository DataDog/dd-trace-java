package datadog.trace.instrumentation.googlepubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

/** Verifies that only full Pub/Sub resource paths are tagged, never short names or placeholders. */
class PubSubResourcePathTest {

  @TableTest({
    "scenario          | name                                     | expectedPath                     ",
    "full topic path   | projects/my-project/topics/orders        | projects/my-project/topics/orders",
    "short topic name  | orders                                   |                                  ",
    "subscription path | projects/my-project/subscriptions/orders |                                  ",
    "deleted topic     | _deleted-topic_                          |                                  ",
    "missing project   | projects//topics/orders                  |                                  ",
    "nested topic      | projects/my-project/topics/orders/extra  |                                  "
  })
  void tagsOnlyFullTopicPaths(String name, String expectedPath) {
    assertEquals(expectedPath, PubSubDecorator.fullTopicPath(name));
  }

  @TableTest({
    "scenario               | name                                              | expectedPath                                     ",
    "full subscription path | projects/analytics/subscriptions/warehouse-orders | projects/analytics/subscriptions/warehouse-orders",
    "short subscription     | warehouse-orders                                  |                                                  ",
    "topic path             | projects/analytics/topics/warehouse-orders        |                                                  ",
    "missing name           | projects/analytics/subscriptions/                 |                                                  "
  })
  void tagsOnlyFullSubscriptionPaths(String name, String expectedPath) {
    assertEquals(expectedPath, PubSubDecorator.fullSubscriptionPath(name));
  }
}
