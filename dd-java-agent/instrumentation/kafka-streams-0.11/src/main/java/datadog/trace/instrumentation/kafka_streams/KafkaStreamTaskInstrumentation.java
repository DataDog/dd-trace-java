package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.createWithGroup;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.kafka_common.StreamingContext.STREAMING_CONTEXT;
import static datadog.trace.instrumentation.kafka_common.Utils.computePayloadSizeBytes;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.BROKER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_CONSUME;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_DELIVER;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.TIME_IN_QUEUE_ENABLED;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextSetter.PR_SETTER;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextVisitor.PR_GETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextSetter.SR_SETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextVisitor.SR_GETTER;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;
import org.apache.kafka.streams.processor.internals.StreamTask;

@AutoService(InstrumenterModule.class)
public class KafkaStreamTaskInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      "datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapterInterface",
      "datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator",
      "datadog.trace.instrumentation.kafka_common.Utils",
      "datadog.trace.instrumentation.kafka_common.StreamingContext",
      packageName + ".KafkaStreamsDecorator",
      packageName + ".ProcessorRecordContextHeadersAccess",
      packageName + ".ProcessorRecordContextVisitor",
      packageName + ".ProcessorRecordContextSetter",
      packageName + ".StampedRecordContextVisitor",
      packageName + ".StampedRecordContextSetter",
      packageName + ".StreamTaskContext",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.apache.kafka.streams.processor.internals.StreamTask",
        packageName + ".StreamTaskContext");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // StreamsConfig was the 4th input argument to StreamTask's constructor in kafka versions 2.6 to
    // 3.1.
    // Starting from 3.2 StreamsConfig was no longer an input argument into StreamTask.
    transformer.applyAdvice(
        isConstructor().and(takesArgument(4, named("org.apache.kafka.streams.StreamsConfig"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$Constructor4Advice");

    // StreamsConfig was the 5th input argument to StreamTask's constructor in kafka versions 1.1 to
    // 2.5
    transformer.applyAdvice(
        isConstructor().and(takesArgument(5, named("org.apache.kafka.streams.StreamsConfig"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$Constructor5Advice");

    // StreamsConfig was the 6th input argument to StreamTask's constructor in kafka versions 0.11
    // to 1.0.
    transformer.applyAdvice(
        isConstructor().and(takesArgument(6, named("org.apache.kafka.streams.StreamsConfig"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$Constructor6Advice");

    transformer.applyAdvice(
        isMethod().and(named("addRecords")).and(takesArgument(1, named("java.lang.Iterable"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$UnwrapIterableAdvice");

    // Before 2.7
    transformer.applyAdvice(
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
    transformer.applyAdvice(
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

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("process"))
            // Method signature changed in 2.6.
            .and(takesArguments(0).or(takesArguments(1).and(takesArgument(0, long.class)))),
        KafkaStreamTaskInstrumentation.class.getName() + "$StopSpanAdvice");
  }

  public static class Constructor4Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This StreamTask task, @Advice.Argument(4) StreamsConfig streamsConfig) {
      String applicationId = streamsConfig.getString(StreamsConfig.APPLICATION_ID_CONFIG);

      if (applicationId != null && !applicationId.isEmpty()) {
        StreamTaskContext context =
            InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
        if (context == null) {
          context = new StreamTaskContext();
        }
        context.setApplicationId(applicationId);
        InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).put(task, context);
      }
    }
  }

  public static class Constructor5Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This StreamTask task, @Advice.Argument(5) StreamsConfig streamsConfig) {
      String applicationId = streamsConfig.getString(StreamsConfig.APPLICATION_ID_CONFIG);

      if (applicationId != null && !applicationId.isEmpty()) {
        StreamTaskContext context =
            InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
        if (context == null) {
          context = new StreamTaskContext();
        }
        context.setApplicationId(applicationId);
        InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).put(task, context);
      }
    }
  }

  public static class Constructor6Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureGroup(
        @Advice.This StreamTask task, @Advice.Argument(6) StreamsConfig streamsConfig) {
      String applicationId = streamsConfig.getString(StreamsConfig.APPLICATION_ID_CONFIG);

      if (applicationId != null && !applicationId.isEmpty()) {
        StreamTaskContext context =
            InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
        if (context == null) {
          context = new StreamTaskContext();
        }
        context.setApplicationId(applicationId);
        InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).put(task, context);
      }
    }
  }

  public static class UnwrapIterableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 1, readOnly = false) Iterable<ConsumerRecord<?, ?>> records) {
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
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        // partition|offset == -1 -> punctuation call.
        return;
      }

      AgentSpan span, queueSpan = null;
      StreamTaskContext streamTaskContext =
          InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        final AgentSpanContext extractedContext =
            extractContextAndGetSpanContext(record, SR_GETTER);
        long timeInQueueStart = SR_GETTER.extractTimeInQueueStart(record);
        if (timeInQueueStart == 0 || !TIME_IN_QUEUE_ENABLED) {
          span = startSpan(KAFKA_CONSUME, extractedContext);
        } else {
          queueSpan =
              startSpan(KAFKA_DELIVER, extractedContext, MILLISECONDS.toMicros(timeInQueueStart));
          BROKER_DECORATE.afterStart(queueSpan);
          BROKER_DECORATE.onTimeInQueue(queueSpan, record);
          span = startSpan(KAFKA_CONSUME, queueSpan.context());
          BROKER_DECORATE.beforeFinish(queueSpan);
          // The queueSpan will be finished after inner span has been activated to ensure that
          // spans are written out together by TraceStructureWriter when running in strict mode
        }

        String applicationId = null;
        if (streamTaskContext != null) {
          applicationId = streamTaskContext.getApplicationId();
        }
        DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());

        final long payloadSize =
            traceConfig().isDataStreamsEnabled() ? computePayloadSizeBytes(record.value) : 0;
        if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())) {
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setCheckpoint(span, create(tags, record.timestamp, payloadSize));
        } else {
          if (STREAMING_CONTEXT.isSourceTopic(record.topic())) {
            Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
            DataStreamsContext dsmContext = create(tags, record.timestamp, payloadSize);
            dsmPropagator.inject(span.with(dsmContext), record, SR_SETTER);
          }
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

      if (streamTaskContext == null) {
        streamTaskContext = new StreamTaskContext();
      }
      streamTaskContext.setAgentScope(agentScope);
      InstrumentationContext.get(StreamTask.class, StreamTaskContext.class)
          .put(task, streamTaskContext);
    }
  }

  /** Very similar to StartSpanAdvice, but with a different argument type for record. */
  public static class StartSpanAdvice27 {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void start(
        @Advice.Argument(0) final ProcessorNode node,
        @Advice.Argument(2) final ProcessorRecordContext record,
        @Advice.This StreamTask task) {
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        // partition|offset == -1 -> punctuation call.
        return;
      }

      AgentSpan span, queueSpan = null;
      StreamTaskContext streamTaskContext =
          InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        final AgentSpanContext extractedContext =
            extractContextAndGetSpanContext(record, PR_GETTER);
        long timeInQueueStart = PR_GETTER.extractTimeInQueueStart(record);
        if (timeInQueueStart == 0 || !TIME_IN_QUEUE_ENABLED) {
          span = startSpan(KAFKA_CONSUME, extractedContext);
        } else {
          queueSpan =
              startSpan(KAFKA_DELIVER, extractedContext, MILLISECONDS.toMicros(timeInQueueStart));
          BROKER_DECORATE.afterStart(queueSpan);
          BROKER_DECORATE.onTimeInQueue(queueSpan, record);
          span = startSpan(KAFKA_CONSUME, queueSpan.context());
          BROKER_DECORATE.beforeFinish(queueSpan);
          // The queueSpan will be finished after inner span has been activated to ensure that
          // spans are written out together by TraceStructureWriter when running in strict mode
        }

        String applicationId = null;
        if (streamTaskContext != null) {
          applicationId = streamTaskContext.getApplicationId();
        }
        DataStreamsTags tags = createWithGroup("kafka", INBOUND, applicationId, record.topic());

        long payloadSize = 0;
        // we have to go through Object to get the RecordMetadata here because the class of `record`
        // only implements it after 2.7 (and this class is only used if v >= 2.7)
        if ((Object) record instanceof RecordMetadata) { // should always be true
          RecordMetadata metadata = (RecordMetadata) (Object) record;
          payloadSize = metadata.serializedKeySize() + metadata.serializedValueSize();
        }

        if (STREAMING_CONTEXT.isDisabledForTopic(record.topic())) {
          AgentTracer.get()
              .getDataStreamsMonitoring()
              .setCheckpoint(span, create(tags, record.timestamp(), payloadSize));
        } else {
          if (STREAMING_CONTEXT.isSourceTopic(record.topic())) {
            Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
            DataStreamsContext dsmContext = create(tags, record.timestamp(), payloadSize);
            dsmPropagator.inject(span.with(dsmContext), record, PR_SETTER);
          }
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

      if (streamTaskContext == null) {
        streamTaskContext = new StreamTaskContext();
      }
      streamTaskContext.setAgentScope(agentScope);
      InstrumentationContext.get(StreamTask.class, StreamTaskContext.class)
          .put(task, streamTaskContext);
    }
  }

  public static class StopSpanAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Thrown final Throwable throwable, @Advice.This StreamTask task) {
      StreamTaskContext streamTaskContext =
          InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
      if (streamTaskContext != null) {
        AgentScope scope = streamTaskContext.getAgentScope();
        if (scope != null) {
          AgentSpan span = scope.span();
          CONSUMER_DECORATE.onError(span, throwable);
          CONSUMER_DECORATE.beforeFinish(span);
          scope.close();
          span.finish();
          streamTaskContext.setAgentScope(null);
        }
      }
    }
  }
}
