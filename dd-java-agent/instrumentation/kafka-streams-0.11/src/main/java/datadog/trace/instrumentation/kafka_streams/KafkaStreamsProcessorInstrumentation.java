package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.kafka_streams.KafkaStreamsDecorator.KAFKA_CONSUME;
import static datadog.trace.instrumentation.kafka_streams.TextMapExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.streams.processor.internals.StampedRecord;

public class KafkaStreamsProcessorInstrumentation {
  // These two instrumentations work together to apply StreamTask.process.
  // The combination of these is needed because there's no good instrumentation point.
  // FIXME: this instrumentation takes somewhat strange approach. It looks like Kafka Streams
  // defines notions of 'processor', 'source' and 'sink'. There is no 'consumer' as such.
  // Also this instrumentation doesn't define 'producer' making it 'asymmetric' - resulting
  // in awkward tests and traces. We may want to revisit this in the future.

  @AutoService(Instrumenter.class)
  public static class StartInstrumentation extends Instrumenter.Tracing {

    public StartInstrumentation() {
      super("kafka", "kafka-streams");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("org.apache.kafka.streams.processor.internals.PartitionGroup");
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        packageName + ".KafkaStreamsDecorator", packageName + ".TextMapExtractAdapter"
      };
    }

    @Override
    public void adviceTransformations(AdviceTransformation transformation) {
      transformation.applyAdvice(
          isMethod()
              .and(isPackagePrivate())
              .and(named("nextRecord"))
              .and(returns(named("org.apache.kafka.streams.processor.internals.StampedRecord"))),
          StartInstrumentation.class.getName() + "$StartSpanAdvice");
    }

    public static class StartSpanAdvice {

      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void onExit(@Advice.Return final StampedRecord record) {
        if (record == null) {
          return;
        }

        final Context extractedContext = propagate().extract(record.value.headers(), GETTER);

        final AgentSpan span = startSpan(KAFKA_CONSUME, extractedContext);
        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span, record);

        activateSpan(span).setAsyncPropagation(true);
      }
    }
  }

  @AutoService(Instrumenter.class)
  public static class StopInstrumentation extends Instrumenter.Tracing {

    public StopInstrumentation() {
      super("kafka", "kafka-streams");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("org.apache.kafka.streams.processor.internals.StreamTask");
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {
        packageName + ".KafkaStreamsDecorator", packageName + ".TextMapExtractAdapter"
      };
    }

    @Override
    public void adviceTransformations(AdviceTransformation transformation) {
      transformation.applyAdvice(
          isMethod().and(isPublic()).and(named("process")).and(takesArguments(0)),
          StopInstrumentation.class.getName() + "$StopSpanAdvice");
    }

    public static class StopSpanAdvice {

      @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
      public static void stopSpan(@Advice.Thrown final Throwable throwable) {
        // This is dangerous... we assume the span/scope is the one we expect, but it may not be.
        final AgentSpan span = activeSpan();
        if (span != null) {
          CONSUMER_DECORATE.onError(span, throwable);
          CONSUMER_DECORATE.beforeFinish(span);
        }
        final TraceScope scope = activeScope();
        if (scope != null) {
          scope.close();
          span.finish();
        }
      }
    }
  }
}
