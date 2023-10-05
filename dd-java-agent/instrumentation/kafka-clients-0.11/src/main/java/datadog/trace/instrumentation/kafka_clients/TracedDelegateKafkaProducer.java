package datadog.trace.instrumentation.kafka_clients;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TracedDelegateKafkaProducer implements Producer {
  private static final Logger log = LoggerFactory.getLogger(TracedDelegateKafkaProducer.class);
  private final Producer delegate;

  public TracedDelegateKafkaProducer(final Producer delegate) {
    this.delegate = delegate;
  }

  @Override
  public void initTransactions() {
    this.delegate.initTransactions();
  }

  @Override
  public void beginTransaction() throws ProducerFencedException {
    this.delegate.beginTransaction();
  }

  @Override
  public void commitTransaction() throws ProducerFencedException {
    this.delegate.commitTransaction();
  }

  @Override
  public void abortTransaction() throws ProducerFencedException {
    this.delegate.abortTransaction();
  }

  @Override
  public Future<RecordMetadata> send(ProducerRecord record) {
    return this.delegate.send(record);
  }

  @Override
  public Future<RecordMetadata> send(ProducerRecord record, Callback callback) {
    return this.delegate.send(record, callback);
  }

  @Override
  public void flush() {
    this.delegate.flush();
  }

  @Override
  public List<PartitionInfo> partitionsFor(String topic) {
    return this.delegate.partitionsFor(topic);
  }

  @Override
  public Map<MetricName, ? extends Metric> metrics() {
    return this.delegate.metrics();
  }

  @Override
  public void close() {
    this.delegate.close();
  }

  @Override
  public void close(long timeout, TimeUnit unit) {
    this.delegate.close(timeout, unit);
  }

  @Override
  public void close(Duration duration) {
    this.delegate.close(duration);
  }

  @Override
  public void sendOffsetsToTransaction(Map offsets, String consumerGroupId)
      throws ProducerFencedException {
    this.delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
  }
}
