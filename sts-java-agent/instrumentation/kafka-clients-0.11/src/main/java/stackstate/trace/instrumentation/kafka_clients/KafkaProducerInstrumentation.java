package stackstate.trace.instrumentation.kafka_clients;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class KafkaProducerInstrumentation extends Instrumenter.Configurable {
  public static final HelperInjector HELPER_INJECTOR =
      new HelperInjector(
          "datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapter",
          KafkaProducerInstrumentation.class.getName() + "$ProducerCallback");

  private static final String OPERATION = "kafka.produce";
  private static final String COMPONENT_NAME = "java-kafka";

  public KafkaProducerInstrumentation() {
    super("kafka");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            named("org.apache.kafka.clients.producer.KafkaProducer"),
            classLoaderHasClasses(
                "org.apache.kafka.common.header.Header", "org.apache.kafka.common.header.Headers"))
        .transform(HELPER_INJECTOR)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(named("send"))
                        .and(
                            takesArgument(
                                0, named("org.apache.kafka.clients.producer.ProducerRecord")))
                        .and(takesArgument(1, named("org.apache.kafka.clients.producer.Callback"))),
                    ProducerAdvice.class.getName()))
        .asDecorator();
  }

  public static class ProducerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.Argument(value = 0, readOnly = false) ProducerRecord record,
        @Advice.Argument(value = 1, readOnly = false) Callback callback) {
      final Scope scope = GlobalTracer.get().buildSpan(OPERATION).startActive(false);
      callback = new ProducerCallback(callback, scope);

      final Span span = scope.span();
      final String topic = record.topic() == null ? "unknown" : record.topic();
      if (record.partition() != null) {
        span.setTag("kafka.partition", record.partition());
      }

      Tags.COMPONENT.set(span, COMPONENT_NAME);
      Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_PRODUCER);

      span.setTag(DDTags.RESOURCE_NAME, "Produce Topic " + topic);
      span.setTag(DDTags.SPAN_TYPE, DDSpanTypes.MESSAGE_PRODUCER);
      span.setTag(DDTags.SERVICE_NAME, "kafka");

      try {
        GlobalTracer.get()
            .inject(
                scope.span().context(),
                Format.Builtin.TEXT_MAP,
                new TextMapInjectAdapter(record.headers()));
      } catch (final IllegalStateException e) {
        //headers must be read-only from reused record. try again with new one.
        record =
            new ProducerRecord<>(
                record.topic(),
                record.partition(),
                record.timestamp(),
                record.key(),
                record.value(),
                record.headers());

        GlobalTracer.get()
            .inject(
                scope.span().context(),
                Format.Builtin.TEXT_MAP,
                new TextMapInjectAdapter(record.headers()));
      }

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final Span span = scope.span();
        Tags.ERROR.set(span, true);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        span.finish();
      }
      scope.close();
    }
  }

  public static class ProducerCallback implements Callback {
    private final Callback callback;
    private final Scope scope;

    public ProducerCallback(final Callback callback, final Scope scope) {
      this.callback = callback;
      this.scope = scope;
    }

    @Override
    public void onCompletion(final RecordMetadata metadata, final Exception exception) {
      if (exception != null) {
        Tags.ERROR.set(scope.span(), Boolean.TRUE);
        scope.span().log(Collections.singletonMap(ERROR_OBJECT, exception));
      }
      try {
        if (callback != null) {
          callback.onCompletion(metadata, exception);
        }
      } finally {
        scope.span().finish();
        scope.close();
      }
    }
  }
}
