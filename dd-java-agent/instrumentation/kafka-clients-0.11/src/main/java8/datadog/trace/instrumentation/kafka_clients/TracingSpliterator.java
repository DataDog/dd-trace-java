package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;

import java.util.Spliterator;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;

public class TracingSpliterator implements Spliterator<ConsumerRecord<?, ?>> {
  private final Spliterator<ConsumerRecord<?, ?>> delegateSpliterator;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;

  public TracingSpliterator(
      final Spliterator<ConsumerRecord<?, ?>> delegateSpliterator,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group) {
    this.delegateSpliterator = delegateSpliterator;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
  }

  @Override
  public boolean tryAdvance(Consumer<? super ConsumerRecord<?, ?>> action) {
    boolean result = this.delegateSpliterator.tryAdvance((rec) -> {
      action.accept(rec);
      IteratorUtils.startNewRecordSpan(rec, this.operationName, this.group, this.decorator);
    });

    if (!result) {
      closePrevious(true);
    }

    return result;
  }

  @Override
  public void forEachRemaining(Consumer<? super ConsumerRecord<?, ?>> action) {
    this.delegateSpliterator.forEachRemaining((rec) -> {
      action.accept(rec);
      IteratorUtils.startNewRecordSpan(rec, this.operationName, this.group, this.decorator);
    });

    closePrevious(true);
  }

  @Override
  public Spliterator<ConsumerRecord<?, ?>> trySplit() {
    Spliterator<ConsumerRecord<?, ?>> split = this.delegateSpliterator.trySplit();
    return split != null ? new TracingSpliterator(split, this.operationName, this.decorator, this.group) : null;
  }

  @Override
  public long estimateSize() {
    return this.delegateSpliterator.estimateSize();
  }

  @Override
  public int characteristics() {
    return this.delegateSpliterator.characteristics();
  }
}
