package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

public final class ContextVisitors {

  private static final MapContextVisitor<?> MAP_CONTEXT_VISITOR = new MapContextVisitor<>();
  private static final EntrySetContextVisitor<?> ENTRY_SET_CONTEXT_VISITOR =
      new EntrySetContextVisitor<>();

  @SuppressWarnings("unchecked")
  public static <T extends Map<String, ?>> AgentPropagation.ContextVisitor<T> objectValuesMap() {
    return (AgentPropagation.ContextVisitor<T>) MAP_CONTEXT_VISITOR;
  }

  @SuppressWarnings("unchecked")
  public static <T extends Map<String, String>>
      AgentPropagation.ContextVisitor<T> stringValuesMap() {
    return (AgentPropagation.ContextVisitor<T>) MAP_CONTEXT_VISITOR;
  }

  @SuppressWarnings("unchecked")
  public static <T extends Iterable<Map.Entry<String, ?>>>
      AgentPropagation.ContextVisitor<T> objectValuesEntrySet() {
    return (AgentPropagation.ContextVisitor<T>) ENTRY_SET_CONTEXT_VISITOR;
  }

  @SuppressWarnings("unchecked")
  public static <T extends Iterable<Map.Entry<String, String>>>
      AgentPropagation.ContextVisitor<T> stringValuesEntrySet() {
    return (AgentPropagation.ContextVisitor<T>) ENTRY_SET_CONTEXT_VISITOR;
  }

  private static final class MapContextVisitor<T extends Map<String, ?>>
      implements AgentPropagation.ContextVisitor<T> {

    @Override
    public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, ?> entry : carrier.entrySet()) {
        if (null != entry.getValue()
            && !classifier.accept(entry.getKey(), entry.getValue().toString())) {
          return;
        }
      }
    }
  }

  private static final class EntrySetContextVisitor<T extends Iterable<Map.Entry<String, ?>>>
      implements AgentPropagation.ContextVisitor<T> {

    @Override
    public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, ?> entry : carrier) {
        if (null != entry.getValue()
            && !classifier.accept(entry.getKey(), entry.getValue().toString())) {
          return;
        }
      }
    }
  }
}
