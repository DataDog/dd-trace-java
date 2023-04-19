package datadog.trace.instrumentation.kafka_streams;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription.Node;
import org.apache.kafka.streams.TopologyDescription.Processor;
import org.apache.kafka.streams.TopologyDescription.Sink;
import org.apache.kafka.streams.TopologyDescription.Source;
import org.apache.kafka.streams.TopologyDescription.Subtopology;

public class TopologyContext {

  public static final int SOURCE_TOPIC = 0;
  public static final int INTERNAL_TOPIC = 1;
  public static final int TARGET_TOPIC = 2;

  public static final String DSM_STREAM_ORIGIN_HEADER = "dd_dsm_stream_origin_base64";

  private static HashSet<String> GetSourceTopics(Source node) {
    String[] allTopics = node.topics().split(",");
    HashSet<String> result = new HashSet<>();

    Collections.addAll(result, allTopics);
    return result;
  }

  private static void AddTopics(Node node, HashSet<String> sinkTopics, HashSet<String> sourceTopics) {
    if (node instanceof Sink) {
      sinkTopics.add(((Sink)node).topic());
    }

    if (node instanceof Source) {
      for (String topic: GetSourceTopics((Source)node)) {
        sourceTopics.add(topic);
      }
    }
  }

  public static void PrintLevel(Node node, HashMap<Node, HashSet<Node>> map, int level) {
    String ident = "";
    for (int i = 0; i < level; i ++) {
      ident += "  ";
    }

    if (node instanceof Sink) {
      System.out.println("####" + ident + node.name() + "(" + ((Sink)node).topic() + ")");
    }

    if (node instanceof Source) {
      System.out.println("####" + ident + node.name() + "(" + String.join(",", GetSourceTopics((Source)node)) + ")");
    }

    if (node instanceof Processor) {
      System.out.println("####" + ident + node.name());
    }

    HashSet<Node> children = map.get(node);
    for (Node child: children) {
      PrintLevel(child, map, level + 1);
    }
  }

  public static void PrintHierarchy(Subtopology subtopology) {
    HashMap<Node, HashSet<Node>> parentChildMap = new HashMap<>();
    HashMap<Node, HashSet<Node>> childParentMap = new HashMap<>();
    HashSet<Node> allNodes = new HashSet<>();

    for (Node node: subtopology.nodes()) {
      if (!parentChildMap.containsKey(node)) {
        parentChildMap.put(node, new HashSet<>());
      }

      if (!childParentMap.containsKey(node)) {
        childParentMap.put(node, new HashSet<>());
      }

      allNodes.add(node);
    }

    for (Node node: allNodes) {
      for (Node suc: node.successors()) {
        parentChildMap.get(node).add(suc);
        childParentMap.get(suc).add(node);
      }
    }

    for (Map.Entry<Node, HashSet<Node>> entry: childParentMap.entrySet()) {
      // process root nodes
      if (entry.getValue().isEmpty()) {
        PrintLevel(entry.getKey(), parentChildMap, 1);
      }
    }
  }

  public static void PrintHierarchy(Topology topology) {
    for (Subtopology sub : topology.describe().subtopologies()) {
      System.out.println("#### Subtoplogy: " + sub.id());
      PrintHierarchy(sub);
    }
  }

  public static void Add(Topology topology) {
    PrintHierarchy(topology);

    HashSet<String> sinks = new HashSet<>();
    HashSet<String> sources = new HashSet<>();

    for (Subtopology sub : topology.describe().subtopologies()) {
      System.out.println("### TOPOLOGY: " + sub.id());
      for (Node node: sub.nodes()) {
        AddTopics(node, sinks, sources);

        for (Node pre: node.predecessors()) {
          AddTopics(pre, sinks, sources);
        }

        for (Node suc: node.successors()){
          AddTopics(suc, sinks, sources);
        }
      }
    }

    HashSet<String> internalTopics = new HashSet<>();

    for (String source: sources) {
      if (sinks.contains(source)) {
        NodeToQueueMap.put(source, INTERNAL_TOPIC);
        internalTopics.add(source);
      } else {
        NodeToQueueMap.put(source, SOURCE_TOPIC);
      }
    }

    for (String sink: sinks) {
      if (!internalTopics.contains(sink)) {
        NodeToQueueMap.put(sink, TARGET_TOPIC);
      }
    }
  }

  public static boolean isSourceTopic(final String topic) {
    return isNodeType(topic, SOURCE_TOPIC);
  }

  public static boolean isTargetTopic(final String topic) {
    return isNodeType(topic, TARGET_TOPIC);
  }

  public static boolean isInternalNode(final String topic) {
    return isNodeType(topic, INTERNAL_TOPIC);
  }

  public static boolean isNodeType(final String topic, int type) {
    if (NodeToQueueMap.containsKey(topic))
    {
      return NodeToQueueMap.get(topic) == type;
    }

    return false;
  }

  private static final ConcurrentHashMap<String, Integer> NodeToQueueMap = new ConcurrentHashMap<>();
}