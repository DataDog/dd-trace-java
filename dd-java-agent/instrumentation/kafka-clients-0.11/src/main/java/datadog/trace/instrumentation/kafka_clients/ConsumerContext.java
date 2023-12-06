package datadog.trace.instrumentation.kafka_clients;

public class ConsumerContext {
  private String consumerGroup;

  private String bootstrapServers;

  public ConsumerContext() {}

  public void setConsumerGroup(String consumerGroup) {
    this.consumerGroup = consumerGroup;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public void setBootstrapServers(String bootstrapServers) {
    this.consumerGroup = bootstrapServers;
  }

  public String getBootstrapServers() {
    return bootstrapServers;
  }
}
