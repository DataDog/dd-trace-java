package datadog.trace.instrumentation.kafka_streams;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;

public class GlobalTopologyContext {

  public static final int SOURCE_TOPIC = 0;
  public static final int INTERNAL_TOPIC = 1;
  public static final int SINK_TOPIC = 2;

  public static void registerTopology(ProcessorTopology topology) {
    allSourceTopics.addAll(topology.sourceTopics());
    allSinkTopics.addAll(topology.sinkTopics());

    HashMap<String, Integer> topics = new HashMap<>();
    for (String sourceTopic: allSourceTopics) {
      if (allSinkTopics.contains(sourceTopic)) {
        topics.put(sourceTopic, INTERNAL_TOPIC);
      } else {
        topics.put(sourceTopic, SOURCE_TOPIC);
      }
    }

    for (String sinkTopic: allSinkTopics) {
      if (!allSourceTopics.contains(sinkTopic)) {
        topics.put(sinkTopic, SINK_TOPIC);
      }
    }

    lock.lock();
    try {
      Topics.clear();
      Topics.putAll(topics);
    } finally {
      lock.unlock();
    }
  }

  public static boolean isSourceTopic(final String topic) {
    return isNodeType(topic, SOURCE_TOPIC);
  }

  public static boolean isTargetTopic(final String topic) {
    return isNodeType(topic, SINK_TOPIC);
  }

  public static boolean isNodeType(final String topic, int type) {
    lock.lock();
    try {
      if (Topics.containsKey(topic))
      {
        return Topics.get(topic) == type;
      }
    } finally {
      lock.unlock();
    }

    return false;
  }

  public static boolean isEmpty() {
    lock.lock();
    try {
      return Topics.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  public static String asString() {
    String result = "";

    lock.lock();
    try
    {
      for (Map.Entry<String,Integer> entry: Topics.entrySet()) {
        result += entry.getKey() + " - " + entry.getValue() + "\n";
      }
    } finally {
      lock.unlock();
    }

    return result;
  }

  private static final Set<String> allSourceTopics = ConcurrentHashMap.newKeySet();
  private static final Set<String> allSinkTopics = ConcurrentHashMap.newKeySet();

  private static final Lock lock = new ReentrantLock();
  private static final ConcurrentHashMap<String, Integer> Topics = new ConcurrentHashMap<>();
}