package datadog.trace.instrumentation.kafka_streams;

import datadog.trace.instrumentation.kafka_clients.StreamingContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;

public class StreamingContextUpdater {

  public static void updateWithTopology(ProcessorTopology topology) {
    Set<String> internalTopics = ConcurrentHashMap.newKeySet();
    Map<String, String> storeToChanglogMap = topology.storeToChangelogTopic();

    for (StateStore store: topology.stateStores()) {
      if (storeToChanglogMap.containsKey(store.name())) {
        internalTopics.add(storeToChanglogMap.get(store.name()));
      }
    }

    StreamingContext.registerTopics(topology.sourceTopics(), topology.sinkTopics(), internalTopics);
  }
}
