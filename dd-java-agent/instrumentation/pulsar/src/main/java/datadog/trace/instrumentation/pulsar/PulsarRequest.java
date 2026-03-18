package datadog.trace.instrumentation.pulsar;

import static datadog.trace.instrumentation.pulsar.UrlParser.parseUrl;

import org.apache.pulsar.client.api.Message;

public final class PulsarRequest extends BasePulsarRequest {
  private final Message<?> message;

  private PulsarRequest(Message<?> message, String destination, UrlData urlData) {
    super(destination, urlData);
    this.message = message;
  }
  

  public static PulsarRequest create(Message<?> message) {
    return new PulsarRequest(message, message.getTopicName(), null);
  }

  public static PulsarRequest create(Message<?> message, String url) {
    return new PulsarRequest(message, message.getTopicName(), parseUrl(url));
  }

  public static PulsarRequest create(Message<?> message, UrlData urlData) {
    return new PulsarRequest(message, message.getTopicName(), urlData);
  }

  public static PulsarRequest create(Message<?> message, ProducerData producerData) {
    return new PulsarRequest(message, producerData.topic, parseUrl(producerData.url));
  }

  public Message<?> getMessage() {
    return message;
  }
}
