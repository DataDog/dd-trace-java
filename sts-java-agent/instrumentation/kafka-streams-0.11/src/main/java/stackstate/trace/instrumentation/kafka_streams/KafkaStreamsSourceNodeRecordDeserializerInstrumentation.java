package stackstate.trace.instrumentation.kafka_streams;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.Instrumenter;

// This is necessary because SourceNodeRecordDeserializer drops the headers.  :-(
@AutoService(Instrumenter.class)
public class KafkaStreamsSourceNodeRecordDeserializerInstrumentation
    extends Instrumenter.Configurable {

  public KafkaStreamsSourceNodeRecordDeserializerInstrumentation() {
    super("kafka", "kafka-streams");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            named("org.apache.kafka.streams.processor.internals.SourceNodeRecordDeserializer"),
            classLoaderHasClasses("org.apache.kafka.streams.state.internals.KeyValueIterators"))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(named("deserialize"))
                        .and(
                            takesArgument(
                                0, named("org.apache.kafka.clients.consumer.ConsumerRecord")))
                        .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecord"))),
                    SaveHeadersAdvice.class.getName()))
        .asDecorator();
  }

  public static class SaveHeadersAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void saveHeaders(
        @Advice.Argument(0) final ConsumerRecord incoming,
        @Advice.Return(readOnly = false) ConsumerRecord result) {
      result =
          new ConsumerRecord<>(
              result.topic(),
              result.partition(),
              result.offset(),
              result.timestamp(),
              TimestampType.CREATE_TIME,
              result.checksum(),
              result.serializedKeySize(),
              result.serializedValueSize(),
              result.key(),
              result.value(),
              incoming.headers());
    }
  }
}
