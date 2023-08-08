package datadog.trace.instrumentation.kafka_clients;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// StreamingContext holds the KStreams topology information
public class StreamingContext {
  private static final Integer UNKNOWN_TOPIC = 0;
  private static final Integer SOURCE_TOPIC = 1;
  private static final Integer INTERNAL_TOPIC = 2;
  private static final Integer SINK_TOPIC = 3;

  public static void registerTopics(
      Set<String> sourceTopics, Set<String> sinkTopics, Set<String> internalTopics) {
    allSourceTopics.addAll(sourceTopics);
    allSinkTopics.addAll(sinkTopics);

    ConcurrentHashMap<String, Integer> newTopics = new ConcurrentHashMap<>();
    for (String sourceTopic : allSourceTopics) {
      if (allSinkTopics.contains(sourceTopic)) {
        newTopics.put(sourceTopic, INTERNAL_TOPIC);
      } else {
        newTopics.put(sourceTopic, SOURCE_TOPIC);
      }
    }

    for (String sinkTopic : allSinkTopics) {
      if (!allSourceTopics.contains(sinkTopic)) {
        newTopics.put(sinkTopic, SINK_TOPIC);
      }
    }

    for (String internalTopic : internalTopics) {
      newTopics.put(internalTopic, INTERNAL_TOPIC);
    }

    topics = newTopics;
  }

  public static boolean isSinkTopic(final String topic) {
    return Objects.equals(topics.getOrDefault(topic, UNKNOWN_TOPIC), SINK_TOPIC);
  }

  public static boolean isSourceTopic(final String topic) {
    return Objects.equals(topics.getOrDefault(topic, UNKNOWN_TOPIC), SOURCE_TOPIC);
  }

  public static boolean empty() {
    return topics.size() == 0;
  }

  private static final Set<String> allSourceTopics = ConcurrentHashMap.newKeySet();

  private static final Set<String> allSinkTopics = ConcurrentHashMap.newKeySet();

  private static ConcurrentHashMap<String, Integer> topics = new ConcurrentHashMap<>();
}
