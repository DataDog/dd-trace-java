package datadog.trace.instrumentation.kafka_clients;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DataStreamsIgnoreContext {

  // Add a topic to the ignore list
  public static void add(String topic) {
    System.out.println("#### ADDED TOPIC TO IGNORE: " + topic);
    topics.add(topic);
  }

  // checks if the topic is
  public static boolean contains(String topic) {
    System.out.println("#### CHECKED TOPIC FOR IGNORE: " + topic);
    return topics.contains(topic);
  }

  public static void print() {
    System.out.println("#### IGNORE TOPICS");
    for (String topic: topics) {
      System.out.println("    #### " + topic);
    }
  }

  private static final Set<String> topics = ConcurrentHashMap.newKeySet();
}
