package com.datadog.appsec.config;

import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.nodes.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class Condition {
  public final Operation operation;

  protected Condition(Operation operation) {
    this.operation = operation;
  }

  /**
   * Constructor for Conditions to provide custom logic
   * Depends on "operation" will create various condition types object
   */
  public static class Constructor extends AbstractConstruct {

    private final DefaultConstructor defaultConstructor;

    public Constructor(DefaultConstructor defaultConstructor) {
      this.defaultConstructor = defaultConstructor;
    }

    /**
     * Converts MappingNode to map of nodes (we assume keys are strings)
     * If keys are not strings - they will be ignored
     */
    private Map<String, Node> constructMapOfNodes(MappingNode node) {
      Map<String, Node> map = new HashMap<>();

      List<NodeTuple> nodeValue = node.getValue();
      for (NodeTuple tuple : nodeValue) {
        Node keyNode = tuple.getKeyNode();
        Node valueNode = tuple.getValueNode();

        String key = defaultConstructor.constructScalar(keyNode);
        if (key != null) {
          map.put(key, valueNode);
        }
      }

      return map;
    }

    @Override
    public Object construct(Node node) {
      Condition condition = null;

      Map<String, Node> fields = constructMapOfNodes((MappingNode) node);

      Node propsNode = fields.get("parameters");
      Node operationNode = fields.get("operation");
      if (operationNode != null) {
        Operation operation = defaultConstructor.constructObject(operationNode, Operation.class);

        switch(operation) {
          case MATCH_REGEX:
            condition = defaultConstructor.constructObject(propsNode, MatchRegexCondition.class);
            break;
          case PHRASE_MATCH:
            condition = defaultConstructor.constructObject(propsNode, PhraseMatchCondition.class);
            break;
          case MATCH_STRING:
            condition = defaultConstructor.constructObject(propsNode, MatchStringCondition.class);
            break;
          default:
        }
      }

      return condition;
    }
  }

  public static class MatchRegexCondition extends Condition {
    public List<String> inputs;
    public String regex;
    public Options options;

    protected MatchRegexCondition() {
      super(Operation.MATCH_REGEX);
    }

    public static class Options {
      public Boolean case_sensitive;
      public Integer min_length;
    }
  }

  public static class PhraseMatchCondition extends Condition {
    public List<String> inputs;
    public List<String> list;

    protected PhraseMatchCondition() {
      super(Operation.PHRASE_MATCH);
    }
  }

  public static class MatchStringCondition extends Condition {
    public List<String> inputs;
    public List<String> text;

    protected MatchStringCondition() {
      super(Operation.MATCH_STRING);
    }
  }
}
