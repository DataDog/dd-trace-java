package datadog.trace.instrumentation.kafka_streams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.kafka_clients.TracingList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;

@AutoService(Instrumenter.class)
public class KafkaStreamTaskInstrumentation extends Instrumenter.Tracing {

  public KafkaStreamTaskInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.kafka.streams.processor.internals.StreamTask");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.kafka_clients.KafkaDecorator",
      "datadog.trace.instrumentation.kafka_clients.TextMapExtractAdapter",
      "datadog.trace.instrumentation.kafka_clients.TracingIterable",
      "datadog.trace.instrumentation.kafka_clients.TracingIterator",
      "datadog.trace.instrumentation.kafka_clients.TracingList",
      "datadog.trace.instrumentation.kafka_clients.TracingListIterator",
      "datadog.trace.instrumentation.kafka_clients.Base64Decoder",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("addRecords")).and(takesArgument(1, named("java.lang.Iterable"))),
        KafkaStreamTaskInstrumentation.class.getName() + "$UnwrapIterableAdvice");
  }

  public static class UnwrapIterableAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 1, readOnly = false) Iterable<ConsumerRecord<?, ?>> records) {
      // This method adds the records to a queue, so we want to bypass the kafka instrumentation
      // since the resulting spans are very short and uninteresting.
      // KafkaStreamsProcessorInstrumentation will create a new span instead.

      // Expecting a TracingList because TaskManager.addRecordsToTasks calls records(partition).
      if (records instanceof TracingList) {
        records = ((TracingList) records).getDelegate();
      }
    }
  }
}
