package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;

// new - this instrumentation is completely new.
// the purpose of this class is to provide us with information on consumer group and cluster ID
@AutoService(InstrumenterModule.class)
public class OffsetCommitCallbackInvokerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public OffsetCommitCallbackInvokerInstrumentation() {
    super("kafka", "kafka-3.8");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.apache.kafka.clients.MetadataRecoveryStrategy"); // since 3.8
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.kafka_common.PendingConfig",
      "datadog.trace.instrumentation.kafka_common.MetadataState",
      packageName + ".KafkaConsumerInfo",
      packageName + ".DDOffsetCommitCallback"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>(2);
    stores.put(instrumentedType(), packageName + ".KafkaConsumerInfo");
    stores.put(
        "org.apache.kafka.clients.Metadata",
        "datadog.trace.instrumentation.kafka_common.MetadataState");
    return stores;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("enqueueUserCallbackInvocation")),
        packageName + ".OffsetCommitCallbackInvokerAdvice");
  }
}
