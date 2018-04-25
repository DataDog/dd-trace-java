package stackstate.trace.instrumentation.kafka_streams;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.streams.processor.internals.StampedRecord;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;

public class KafkaStreamsProcessorInstrumentation {
  // These two instrumentations work together to apply StreamTask.process.
  // The combination of these are needed because there's not a good instrumentation point.

  public static final HelperInjector HELPER_INJECTOR =
      new HelperInjector("stackstate.trace.instrumentation.kafka_streams.TextMapExtractAdapter");

  @AutoService(Instrumenter.class)
  public static class StartInstrumentation extends Instrumenter.Configurable {

    public StartInstrumentation() {
      super("kafka", "kafka-streams");
    }

    @Override
    public AgentBuilder apply(final AgentBuilder agentBuilder) {
      return agentBuilder
          .type(
              named("org.apache.kafka.streams.processor.internals.PartitionGroup"),
              classLoaderHasClasses("org.apache.kafka.streams.state.internals.KeyValueIterators"))
          .transform(HELPER_INJECTOR)
          .transform(STSTransformers.defaultTransformers())
          .transform(
              STSAdvice.create()
                  .advice(
                      isMethod()
                          .and(isPackagePrivate())
                          .and(named("nextRecord"))
                          .and(
                              returns(
                                  named(
                                      "org.apache.kafka.streams.processor.internals.StampedRecord"))),
                      StartSpanAdvice.class.getName()))
          .asDecorator();
    }

    public static class StartSpanAdvice {

      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void startSpan(@Advice.Return final StampedRecord record) {
        if (record == null) {
          return;
        }

        final SpanContext extractedContext =
            GlobalTracer.get()
                .extract(
                    Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(record.value.headers()));

        GlobalTracer.get()
            .buildSpan("kafka.consume")
            .asChildOf(extractedContext)
            .withTag(STSTags.SERVICE_NAME, "kafka")
            .withTag(STSTags.RESOURCE_NAME, "Consume Topic " + record.topic())
            .withTag(STSTags.SPAN_TYPE, STSSpanTypes.MESSAGE_CONSUMER)
            .withTag(Tags.COMPONENT.getKey(), "java-kafka")
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER)
            .withTag("partition", record.partition())
            .withTag("offset", record.offset())
            .startActive(true);
      }
    }
  }

  @AutoService(Instrumenter.class)
  public static class StopInstrumentation extends Instrumenter.Configurable {

    public StopInstrumentation() {
      super("kafka", "kafka-streams");
    }

    @Override
    public AgentBuilder apply(final AgentBuilder agentBuilder) {
      return agentBuilder
          .type(
              named("org.apache.kafka.streams.processor.internals.StreamTask"),
              classLoaderHasClasses(
                  "org.apache.kafka.common.header.Header",
                  "org.apache.kafka.common.header.Headers"))
          .transform(HELPER_INJECTOR)
          .transform(STSTransformers.defaultTransformers())
          .transform(
              STSAdvice.create()
                  .advice(
                      isMethod().and(isPublic()).and(named("process")).and(takesArguments(0)),
                      StartSpanAdvice.class.getName()))
          .asDecorator();
    }

    public static class StartSpanAdvice {

      @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
      public static void stopSpan(@Advice.Thrown final Throwable throwable) {
        final Scope scope = GlobalTracer.get().scopeManager().active();
        if (scope != null) {
          if (throwable != null) {
            final Span span = scope.span();
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
          }
          scope.close();
        }
      }
    }
  }
}
