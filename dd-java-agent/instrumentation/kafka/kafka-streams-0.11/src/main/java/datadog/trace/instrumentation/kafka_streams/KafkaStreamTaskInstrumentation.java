package datadog.trace.instrumentation.kafka_streams;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.ProcessorRecordContextVisitor.PR_GETTER;
import static datadog.trace.instrumentation.kafka_streams.StampedRecordContextVisitor.SR_GETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.kafka_clients.TracingIterableDelegator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.StampedRecord;
import org.apache.kafka.streams.processor.internals.StreamTask;

@AutoService(InstrumenterModule.class)
public class KafkaStreamTaskInstrumentation extends InstrumenterModule.DataStreams
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public KafkaStreamTaskInstrumentation() {
    super(KafkaStreamsDecorator.INTEGRATION_NAME, KafkaStreamsDecorator.LEGACY_INTEGRATION_NAME);
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
    transformer.applyAdvices(
        isMethod()
            .and(named("updateProcessorContext"))
            .and(
                takesArgument(
                    0, named("org.apache.kafka.streams.processor.internals.StampedRecord")))
            .and(
                takesArgument(
                    1, named("org.apache.kafka.streams.processor.internals.ProcessorNode"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$ContextPropagationAdvice",
        KafkaStreamTaskInstrumentation.class.getName() + "$StartSpanAdvice");
    // After 2.7
    transformer.applyAdvices(
        isMethod()
            .and(named("updateProcessorContext"))
            .and(
                takesArgument(
                    0, named("org.apache.kafka.streams.processor.internals.ProcessorNode")))
            .and(
                takesArgument(
                    2,
                    named("org.apache.kafka.streams.processor.internals.ProcessorRecordContext"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$ContextPropagationAdvice27",
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

  /** Context propagation for updateProcessorContext before 2.7 (StampedRecord). */
  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextPropagationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final StampedRecord record,
        @Advice.Local("ctxScope") ContextScope scope) {
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        return;
      }
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        scope = defaultPropagator().extract(rootContext(), record, SR_GETTER).attach();
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Local("ctxScope") ContextScope scope) {
      if (scope != null) scope.close();
    }
  }

  /** Context propagation for updateProcessorContext after 2.7 (ProcessorRecordContext). */
  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextPropagationAdvice27 {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(2) final ProcessorRecordContext record,
        @Advice.Local("ctxScope") ContextScope scope) {
      if (record == null || record.partition() == -1 || record.offset() == -1) {
        return;
      }
      if (!Config.get().isKafkaClientPropagationDisabledForTopic(record.topic())) {
        scope = defaultPropagator().extract(rootContext(), record, PR_GETTER).attach();
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Local("ctxScope") ContextScope scope) {
      if (scope != null) scope.close();
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

      StreamTaskContext streamTaskContext =
          InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
      String applicationId =
          streamTaskContext != null ? streamTaskContext.getApplicationId() : null;

      final AgentSpan span =
          !KafkaStreamsDecorator.TRACING_ENABLED && traceConfig().isDataStreamsEnabled()
              ? KafkaStreamsDecorator.startDsmOnlyPathwaySpan(record, applicationId)
              : KafkaStreamsDecorator.startTracedConsumeSpan(record, node, applicationId);

      AgentScope agentScope = activateSpan(span);

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

      StreamTaskContext streamTaskContext =
          InstrumentationContext.get(StreamTask.class, StreamTaskContext.class).get(task);
      String applicationId =
          streamTaskContext != null ? streamTaskContext.getApplicationId() : null;

      final AgentSpan span =
          !KafkaStreamsDecorator.TRACING_ENABLED && traceConfig().isDataStreamsEnabled()
              ? KafkaStreamsDecorator.startDsmOnlyPathwaySpan(record, applicationId)
              : KafkaStreamsDecorator.startTracedConsumeSpan(record, node, applicationId);

      AgentScope agentScope = activateSpan(span);

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
