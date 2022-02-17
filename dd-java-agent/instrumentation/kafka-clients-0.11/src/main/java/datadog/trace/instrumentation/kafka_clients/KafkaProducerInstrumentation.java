package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_LEGACY_TRACING;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.KAFKA_PRODUCE;
import static datadog.trace.instrumentation.kafka_clients.KafkaDecorator.PRODUCER_DECORATE;
import static datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.record.RecordBatch;

@AutoService(Instrumenter.class)
public final class KafkaProducerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaProducerInstrumentation() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.producer.KafkaProducer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator",
      packageName + ".TextMapInjectAdapter",
      packageName + ".KafkaProducerCallback",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.apache.kafka.clients.producer.ProducerRecord")))
            .and(takesArgument(1, named("org.apache.kafka.clients.producer.Callback"))),
        KafkaProducerInstrumentation.class.getName() + "$ProducerAdvice");
  }

  public static class ProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.FieldValue("apiVersions") final ApiVersions apiVersions,
        @Advice.Argument(value = 0, readOnly = false) ProducerRecord record,
        @Advice.Argument(value = 1, readOnly = false) Callback callback) {
      final AgentSpan parent = activeSpan();
      final AgentSpan span = startSpan(KAFKA_PRODUCE);
      PRODUCER_DECORATE.afterStart(span);
      PRODUCER_DECORATE.onProduce(span, record);

      callback = new KafkaProducerCallback(callback, parent, span);

      if (record.value() == null) {
        span.setTag(InstrumentationTags.TOMBSTONE, true);
      }

      // Do not inject headers for batch versions below 2
      // This is how similar check is being done in Kafka client itself:
      // https://github.com/apache/kafka/blob/05fcfde8f69b0349216553f711fdfc3f0259c601/clients/src/main/java/org/apache/kafka/common/record/MemoryRecordsBuilder.java#L411-L412
      // Also, do not inject headers if specified by JVM option or environment variable
      // This can help in mixed client environments where clients < 0.11 that do not support
      // headers attempt to read messages that were produced by clients > 0.11 and the magic
      // value of the broker(s) is >= 2
      if (apiVersions.maxUsableProduceMagic() >= RecordBatch.MAGIC_VALUE_V2
          && Config.get().isKafkaClientPropagationEnabled()
          && !Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        try {
          propagate().inject(span, record.headers(), SETTER);
        } catch (final IllegalStateException e) {
          // headers must be read-only from reused record. try again with new one.
          record =
              new ProducerRecord<>(
                  record.topic(),
                  record.partition(),
                  record.timestamp(),
                  record.key(),
                  record.value(),
                  record.headers());

          propagate().inject(span, record.headers(), SETTER);
        }
        if (!KAFKA_LEGACY_TRACING) {
          SETTER.injectTimeInQueue(record.headers());
        }
      }

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (null == throwable) {
        // emit checkpoint here to capture serialization activity in KafkaProducer::doSend
        // between the start event and this event
        scope.span().startThreadMigration();
      }
      PRODUCER_DECORATE.onError(scope, throwable);
      PRODUCER_DECORATE.beforeFinish(scope);
      scope.close();
    }
  }
}
