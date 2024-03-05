package datadog.trace.instrumentation.kafka_clients;

import java.lang.reflect.Field;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.PartitionStates;

public class ConsumerCoordinatorInstrumentationHelper {

  public static PartitionStates getPartitionStates(SubscriptionState state) {
    if (state == null) {
      return null;
    }
    try {
      Field field = SubscriptionState.class.getDeclaredField("assignment");
      field.setAccessible(true);
      return (PartitionStates) field.get(state);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }

  public static Long getHighWatermark(PartitionStates partitionStates, TopicPartition tp) {
    if (partitionStates == null) {
      return null;
    }
    Object state = partitionStates.stateValue(tp);
    if (state == null) {
      return null;
    }
    try {
      Class<?> clazz = state.getClass();
      Field field = clazz.getDeclaredField("highWatermark");
      field.setAccessible(true);
      return (Long) field.get(state);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }
}
