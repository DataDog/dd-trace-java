package datadog.trace.instrumentation.pulsar;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.HashMap;
import java.util.Map;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.impl.TopicMessageImpl;

public class MessageStore {
  private static final Map<Message<?>, AgentScope> MSG_FIELD =
      new HashMap<Message<?>,AgentScope>();

  public static void Inject(Message<?> instance, AgentScope scope){
    if (instance instanceof TopicMessageImpl<?>) {
      TopicMessageImpl<?> topicMessage = (TopicMessageImpl<?>) instance;
      instance = topicMessage.getMessage();
    }
    if (instance != null) {
      MSG_FIELD.put(instance, scope);
    }
  }

  public static AgentScope extract(Message<?> instance) {
    if (instance instanceof TopicMessageImpl<?>) {
      TopicMessageImpl<?> topicMessage = (TopicMessageImpl<?>) instance;
      instance = topicMessage.getMessage();
    }
    if (instance == null) {
      return null;
    }
    AgentScope  as =  MSG_FIELD.get(instance);
    MSG_FIELD.remove(instance);
    return as;
  }
}
