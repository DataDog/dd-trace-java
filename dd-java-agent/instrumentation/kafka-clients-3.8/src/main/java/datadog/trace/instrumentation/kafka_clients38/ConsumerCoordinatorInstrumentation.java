package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class ConsumerCoordinatorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ConsumerCoordinatorInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isExperimentalKafkaEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>(2);
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator",
        KafkaConsumerInfo.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".KafkaConsumerInfo"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("sendOffsetCommitRequest")).and(takesArguments(1)),
        packageName + ".ConsumerCoordinatorAdvice");
  }
}
