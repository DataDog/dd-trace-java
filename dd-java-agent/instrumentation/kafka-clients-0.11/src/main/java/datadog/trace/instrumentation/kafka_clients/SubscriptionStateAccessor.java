package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.TopicPartition;

public class SubscriptionStateAccessor {
  public static Long partitionLag(SubscriptionState state) {
    return state.partitionLag(new TopicPartition("test_topic", 3), null);
  }
}
