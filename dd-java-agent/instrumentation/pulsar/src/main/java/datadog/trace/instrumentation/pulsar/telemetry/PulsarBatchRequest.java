package datadog.trace.instrumentation.pulsar.telemetry;

import datadog.trace.instrumentation.pulsar.UrlParser;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;

import static datadog.trace.instrumentation.pulsar.UrlParser.parseUrl;

public class PulsarBatchRequest extends BasePulsarRequest{
  private final Messages<?> messages;

  private PulsarBatchRequest(Messages<?> messages, String destination, UrlParser.UrlData urlData) {
    super(destination, urlData);
    this.messages = messages;
  }

  public static PulsarBatchRequest create(Messages<?> messages, String url) {
    return new PulsarBatchRequest(messages, getTopicName(messages), parseUrl(url));
  }

  private static String getTopicName(Messages<?> messages) {
    String topicName = null;
    for (Message<?> message : messages) {
      String name = message.getTopicName();
      if (topicName == null) {
        topicName = name;
      } else if (!topicName.equals(name)) {
        return null;
      }
    }
    return topicName;
  }

  public Messages<?> getMessages() {
    return messages;
  }

}
