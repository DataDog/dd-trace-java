package datadog.trace.instrumentation.kafka_connect;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.kafka_common.ConnectInternalTopics;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.config.AbstractConfig;

/**
 * Reads the Connect internal topic names from the distributed worker config, so Data Streams skips
 * checkpoints on them. This also covers MirrorMaker 2 in dedicated mode, which builds one worker
 * config per replication flow.
 */
@AutoService(InstrumenterModule.class)
public final class ConnectInternalTopicsInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ConnectInternalTopicsInstrumentation() {
    super("kafka", "kafka-connect");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.connect.runtime.distributed.DistributedConfig";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.kafka_common.ConnectInternalTopics"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), ConnectInternalTopicsInstrumentation.class.getName() + "$ConfigAdvice");
  }

  public static class ConfigAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This AbstractConfig config) {
      ConnectInternalTopics.registerConfigured(config.getString("offset.storage.topic"));
      ConnectInternalTopics.registerConfigured(config.getString("config.storage.topic"));
      ConnectInternalTopics.registerConfigured(config.getString("status.storage.topic"));
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // Match ConnectWorkerInstrumentation: only Kafka versions with record headers
      record.headers();
    }
  }
}
