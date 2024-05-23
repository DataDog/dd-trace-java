package datadog.trace.instrumentation.pulsar;

public final class ProducerData {
  public final String url;
  public final String topic;

  private ProducerData(String url, String topic) {
    this.url = url;
    this.topic = topic;
  }

  public static ProducerData create(String url, String topic) {
    return new ProducerData(url, topic);
  }
}
