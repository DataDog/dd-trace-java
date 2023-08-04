package datadog.trace.instrumentation.kafka_streams;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;

public class GlobalTopologyContext {

  private static final Integer UNKNOWN_TOPIC = 0;
  private static final Integer SOURCE_TOPIC = 1;
  private static final Integer INTERNAL_TOPIC = 2;
  private static final Integer SINK_TOPIC = 3;

  public static void registerTopology(ProcessorTopology topology) {
    allSourceTopics.addAll(topology.sourceTopics());
    allSinkTopics.addAll(topology.sinkTopics());

    ConcurrentHashMap<String, Integer> newTopics = new ConcurrentHashMap<>();
    for (String sourceTopic: allSourceTopics) {
      if (allSinkTopics.contains(sourceTopic)) {
        newTopics.put(sourceTopic, INTERNAL_TOPIC);
      } else {
        newTopics.put(sourceTopic, SOURCE_TOPIC);
      }
    }

    for (String sinkTopic: allSinkTopics) {
      if (!allSourceTopics.contains(sinkTopic)) {
        newTopics.put(sinkTopic, SINK_TOPIC);
      }
    }

    Map<String, String> storeToChanglogMap = topology.storeToChangelogTopic();
    for (StateStore store: topology.stateStores()) {
      if (storeToChanglogMap.containsKey(store.name())) {
        newTopics.put(storeToChanglogMap.get(store.name()), INTERNAL_TOPIC);
      }
    }

    topics = newTopics;
  }

  public static boolean isSinkTopic(final String topic) {
    return Objects.equals(topics.getOrDefault(topic, UNKNOWN_TOPIC), SINK_TOPIC);
  }

  public static Set<String> getInternalTopics() {
    HashSet<String> result = new HashSet<>();

    for (Map.Entry<String, Integer> entry: topics.entrySet()) {
      if (Objects.equals(entry.getValue(), INTERNAL_TOPIC)) {
        result.add(entry.getKey());
      }
    }

    return result;
  }

  private static final Set<String> allSourceTopics = ConcurrentHashMap.newKeySet();

  private static final Set<String> allSinkTopics = ConcurrentHashMap.newKeySet();

  private static ConcurrentHashMap<String, Integer> topics = new ConcurrentHashMap<>();
}
