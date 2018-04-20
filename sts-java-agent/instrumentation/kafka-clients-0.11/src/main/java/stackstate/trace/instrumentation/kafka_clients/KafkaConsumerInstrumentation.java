package stackstate.trace.instrumentation.kafka_clients;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Iterator;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;

@AutoService(Instrumenter.class)
public final class KafkaConsumerInstrumentation extends Instrumenter.Configurable {
  public static final HelperInjector HELPER_INJECTOR =
      new HelperInjector(
          "stackstate.trace.instrumentation.kafka_clients.TextMapExtractAdapter",
          "stackstate.trace.instrumentation.kafka_clients.TracingIterable",
          "stackstate.trace.instrumentation.kafka_clients.TracingIterable$TracingIterator",
          "stackstate.trace.instrumentation.kafka_clients.TracingIterable$SpanBuilderDecorator",
          "stackstate.trace.instrumentation.kafka_clients.KafkaConsumerInstrumentation$ConsumeScopeAction");

  public KafkaConsumerInstrumentation() {
    super("kafka");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            named("org.apache.kafka.clients.consumer.ConsumerRecords"),
            classLoaderHasClasses(
                "org.apache.kafka.common.header.Header", "org.apache.kafka.common.header.Headers"))
        .transform(HELPER_INJECTOR)
        .transform(STSTransformers.defaultTransformers())
        .transform(
            STSAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(named("records"))
                        .and(takesArgument(0, String.class))
                        .and(returns(Iterable.class)),
                    IterableAdvice.class.getName())
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(named("iterator"))
                        .and(takesArguments(0))
                        .and(returns(Iterator.class)),
                    IteratorAdvice.class.getName()))
        .asDecorator();
  }

  public static class IterableAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(@Advice.Return(readOnly = false) Iterable<ConsumerRecord> iterable) {
      iterable = new TracingIterable(iterable, "kafka.consume", ConsumeScopeAction.INSTANCE);
    }
  }

  public static class IteratorAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void wrap(@Advice.Return(readOnly = false) Iterator<ConsumerRecord> iterator) {
      iterator =
          new TracingIterable.TracingIterator(
              iterator, "kafka.consume", ConsumeScopeAction.INSTANCE);
    }
  }

  public static class ConsumeScopeAction
      implements TracingIterable.SpanBuilderDecorator<ConsumerRecord> {
    public static final ConsumeScopeAction INSTANCE = new ConsumeScopeAction();

    @Override
    public void decorate(final Tracer.SpanBuilder spanBuilder, final ConsumerRecord record) {
      final String topic = record.topic() == null ? "unknown" : record.topic();
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
