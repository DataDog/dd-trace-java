package datadog.trace.instrumentation.kafka_clients;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
DataStreamsIgnoreContext is a global context containing topics
consume/produce operations for which should be ignored
*/
public class DataStreamsIgnoreContext {
  private final static  int maxTopics = 1000;

  // Add a topic to the ignore list
  public static void add(String topic) {
    if (topics.size() < maxTopics) {
      topics.add(topic);
    }
  }

  // checks if the topic is
  public static boolean contains(String topic) {
    return topics.contains(topic);
  }

  private static final Set<String> topics = ConcurrentHashMap.newKeySet();
}
