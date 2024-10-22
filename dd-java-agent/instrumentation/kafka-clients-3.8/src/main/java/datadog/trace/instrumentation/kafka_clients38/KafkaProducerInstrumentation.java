package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class KafkaProducerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public KafkaProducerInstrumentation() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.producer.KafkaProducer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaDecorator",
      packageName + ".TextMapInjectAdapterInterface",
      packageName + ".TextMapInjectAdapter",
      packageName + ".NoopTextMapInjectAdapter",
      packageName + ".KafkaProducerCallback",
      "datadog.trace.instrumentation.kafka_common.StreamingContext",
      packageName + ".AvroSchemaExtractor",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.kafka.clients.Metadata", "java.lang.String");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.apache.kafka.clients.producer.ProducerRecord")))
            .and(takesArgument(1, named("org.apache.kafka.clients.producer.Callback"))),
        packageName + ".ProducerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(takesArgument(0, int.class))
            .and(named("ensureValidRecordSize")), // intercepting this call allows us to see the
        // estimated message size
        packageName + ".PayloadSizeAdvice");
  }
}
