package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_CONSUME;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_DELIVER;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_LEGACY_TRACING;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextVisitor.PR_GETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextVisitor.SR_GETTER;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;
import org.apache.kafka.streams.processor.internals.StreamTask;

@AutoService(Instrumenter.class)
public class KafkaStreamTaskInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaStreamTaskInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.streams.processor.internals.StreamTask";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator",
      packageName + ".KafkaStreamsDecorator",
      packageName + ".ProcessorRecordContextVisitor",
      packageName + ".StampedRecordContextVisitor",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.apache.kafka.streams.processor.internals.StreamTask", AgentScope.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("addRecords")).and(takesArgument(1, named("java.lang.Iterable"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$UnwrapIterableAdvice");

    // Before 2.7
    transformation.applyAdvice(
        isMethod()
            .and(named("updateProcessorContext"))
            .and(
                takesArgument(
                    0, named("org.apache.kafka.streams.processor.internals.StampedRecord")))
            .and(
                takesArgument(
                    1, named("org.apache.kafka.streams.processor.internals.ProcessorNode"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$StartSpanAdvice");
    // After 2.7
    transformation.applyAdvice(
        isMethod()
            .and(named("updateProcessorContext"))
            .and(
                takesArgument(
                    0, named("org.apache.kafka.streams.processor.internals.ProcessorNode")))
            .and(
                takesArgument(
                    2,
                    named("org.apache.kafka.streams.processor.internals.ProcessorRecordContext"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$StartSpanAdvice27");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("process"))
            // Method signature changed in 2.6.
            .and(takesArguments(0).or(takesArguments(1).and(takesArgument(0, long.class)))),
        KafkaStreamTaskInstrumentation.class.getName() + "$StopSpanAdvice");
  }

  public static class UnwrapIterableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 1, readOnly = false) Iterable<ConsumerRecord<?, ?>> records) {
      System.out.println("[TEST_LOG] UnwrapIterableAdvice.onMethodEnter");
      // This method adds the records to a queue, so we want to bypass the kafka instrumentation
      // since the resulting spans are very short and uninteresting.
      // KafkaStreamsProcessorInstrumentation will create a new span instead.

      // Expecting a TracingList because TaskManager.addRecordsToTasks calls records(partition).
      if (records instanceof TracingIterableDelegator) {
        records = ((TracingIterableDelegator) records).getDelegate();
      }
    }
  }

  /** Very similar to StartSpanAdvice27, but with a different argument type for record. */
  public static class StartSpanAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void start(
        @Advice.Argument(0) final StampedRecord record,
        @Advice.Argument(1) final ProcessorNode node,
        @Advice.This StreamTask task) {
      System.out.println("[TEST_LOG] StartSpanAdvice.onMethodEnter");
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        // partition|offset == -1 -> punctuation call.
        return;
      }

      AgentSpan span, queueSpan = null;
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        final AgentSpan.Context extractedContext = propagate().extract(record, SR_GETTER);
        long timeInQueueStart = SR_GETTER.extractTimeInQueueStart(record);
        if (timeInQueueStart == 0 || KAFKA_LEGACY_TRACING) {
          span = startSpan(KAFKA_CONSUME, extractedContext);
        } else {
          queueSpan =
              startSpan(
                  KAFKA_DELIVER, extractedContext, MILLISECONDS.toMicros(timeInQueueStart), false);
          BROKER_DECORATE.afterStart(queueSpan);
          BROKER_DECORATE.onTimeInQueue(queueSpan, record);
          span = startSpan(KAFKA_CONSUME, queueSpan.context());
          BROKER_DECORATE.beforeFinish(queueSpan);
          // The queueSpan will be finished after inner span has been activated to ensure that
          // spans are written out together by TraceStructureWriter when running in strict mode
        }
      } else {
        span = startSpan(KAFKA_CONSUME, null);
      }

      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onConsume(span, record, node);
      AgentScope agentScope = activateSpan(span);
      if (null != queueSpan) {
        queueSpan.finish();
      }

      InstrumentationContext.get(StreamTask.class, AgentScope.class).put(task, agentScope);
    }
  }

  /** Very similar to StartSpanAdvice, but with a different argument type for record. */
  public static class StartSpanAdvice27 {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void start(
        @Advice.Argument(0) final ProcessorNode node,
        @Advice.Argument(2) final ProcessorRecordContext record,
        @Advice.This StreamTask task) {
      System.out.println("[TEST_LOG] StartSpanAdvice27.onMethodEnter");
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        // partition|offset == -1 -> punctuation call.
        return;
      }

      AgentSpan span, queueSpan = null;
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        final AgentSpan.Context extractedContext = propagate().extract(record, PR_GETTER);
        long timeInQueueStart = PR_GETTER.extractTimeInQueueStart(record);
        if (timeInQueueStart == 0 || KAFKA_LEGACY_TRACING) {
          span = startSpan(KAFKA_CONSUME, extractedContext);
        } else {
          queueSpan =
              startSpan(
                  KAFKA_DELIVER, extractedContext, MILLISECONDS.toMicros(timeInQueueStart), false);
          BROKER_DECORATE.afterStart(queueSpan);
          BROKER_DECORATE.onTimeInQueue(queueSpan, record);
          span = startSpan(KAFKA_CONSUME, queueSpan.context());
          BROKER_DECORATE.beforeFinish(queueSpan);
          // The queueSpan will be finished after inner span has been activated to ensure that
          // spans are written out together by TraceStructureWriter when running in strict mode
        }
      } else {
        span = startSpan(KAFKA_CONSUME, null);
      }

      CONSUMER_DECORATE.afterStart(span);
      CONSUMER_DECORATE.onConsume(span, record, node);
      AgentScope agentScope = activateSpan(span);
      if (null != queueSpan) {
        queueSpan.finish();
      }

      InstrumentationContext.get(StreamTask.class, AgentScope.class).put(task, agentScope);
    }
  }

  public static class StopSpanAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Thrown final Throwable throwable, @Advice.This StreamTask task) {
      AgentScope scope =
          InstrumentationContext.get(StreamTask.class, AgentScope.class).remove(task);
      if (scope != null) {
        AgentSpan span = scope.span();
        CONSUMER_DECORATE.onError(span, throwable);
        CONSUMER_DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      }
    }
  }
}
