package com.datadog.appsec.config;

import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

public interface DefaultConstructor {
  Object constructObject(Node node);

  @SuppressWarnings("unchecked")
  default <T> T constructObject(Node node, Class<T> type) {
    node.setType(type);
    return (T) constructObject(node);
  }

  default String constructScalar(Node node) {
    if (node instanceof ScalarNode) {
      return ((ScalarNode) node).getValue();
    }
    return null;
  }
}
