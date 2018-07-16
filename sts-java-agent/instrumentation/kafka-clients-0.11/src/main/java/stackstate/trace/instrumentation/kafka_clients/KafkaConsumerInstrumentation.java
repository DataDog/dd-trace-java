package stackstate.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;

@AutoService(Instrumenter.class)
public final class KafkaConsumerInstrumentation extends Instrumenter.Default {
  private static final String[] HELPER_CLASS_NAMES =
      new String[] {
        "stackstate.trace.instrumentation.kafka_clients.TextMapExtractAdapter",
        "stackstate.trace.instrumentation.kafka_clients.TracingIterable",
        "stackstate.trace.instrumentation.kafka_clients.TracingIterable$TracingIterator",
        "stackstate.trace.instrumentation.kafka_clients.TracingIterable$SpanBuilderDecorator",
        "stackstate.trace.instrumentation.kafka_clients.KafkaConsumerInstrumentation$ConsumeScopeAction"
      };

  public KafkaConsumerInstrumentation() {
    super("kafka");
  }

  @Override
  public ElementMatcher typeMatcher() {
    return named("org.apache.kafka.clients.consumer.ConsumerRecords");
  }

  @Override
  public ElementMatcher<? super ClassLoader> classLoaderMatcher() {
    return classLoaderHasClasses(
        "org.apache.kafka.common.header.Header", "org.apache.kafka.common.header.Headers");
  }

  @Override
  public String[] helperClassNames() {
    return HELPER_CLASS_NAMES;
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(isPublic())
            .and(named("records"))
            .and(takesArgument(0, String.class))
            .and(returns(Iterable.class)),
        IterableAdvice.class.getName());
    transformers.put(
        isMethod()
            .and(isPublic())
            .and(named("iterator"))
            .and(takesArguments(0))
            .and(returns(Iterator.class)),
        IteratorAdvice.class.getName());
    return transformers;
  }

  public static class IterableAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(@Advice.Return(readOnly = false) Iterable<ConsumerRecord> iterable) {
      if (iterable != null) {
        iterable = new TracingIterable<>(iterable, "kafka.consume", ConsumeScopeAction.INSTANCE);
      }
    }
  }

  public static class IteratorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(@Advice.Return(readOnly = false) Iterator<ConsumerRecord> iterator) {
      if (iterator != null) {
        iterator =
            new TracingIterable.TracingIterator<>(
                iterator, "kafka.consume", ConsumeScopeAction.INSTANCE);
      }
    }
  }

  public static class ConsumeScopeAction
      implements TracingIterable.SpanBuilderDecorator<ConsumerRecord> {
    public static final ConsumeScopeAction INSTANCE = new ConsumeScopeAction();

    @Override
    public void decorate(final Tracer.SpanBuilder spanBuilder, final ConsumerRecord record) {
      final String topic = record.topic() == null ? "kafka" : record.topic();
      final SpanContext spanContext =
          GlobalTracer.get()
              .extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(record.headers()));
      spanBuilder
          .asChildOf(spanContext)
          .withTag(STSTags.SERVICE_NAME, "kafka")
          .withTag(STSTags.RESOURCE_NAME, "Consume Topic " + topic)
          .withTag(STSTags.SPAN_TYPE, STSSpanTypes.MESSAGE_CONSUMER)
          .withTag(Tags.COMPONENT.getKey(), "java-kafka")
          .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER)
          .withTag("partition", record.partition())
          .withTag("offset", record.offset());
    }
  }
}
