package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.core.datastreams.TagsProcessor.KAFKA_CLUSTER_ID_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.PARTITION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.TopicPartition;

@AutoService(Instrumenter.class)
public final class SubscriptionStateInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SubscriptionStateInstrumentation() {
    super("kafka");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("org.apache.kafka.clients.Metadata", "java.lang.String");
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.SubscriptionState",
        KafkaConsumerInfo.class.getName());
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KafkaConsumerInfo",
    };
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.SubscriptionState";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("updateHighWatermark")).and(takesArguments(2)),
        SubscriptionStateInstrumentation.class.getName() + "$HighWatermarkAdvice");
  }

  public static class HighWatermarkAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void trackHighWatermark(
        @Advice.This SubscriptionState subscriptionState,
        @Advice.Argument(0) TopicPartition partition,
        @Advice.Argument(1) long highWatermark) {
      KafkaConsumerInfo kafkaConsumerInfo =
          InstrumentationContext.get(SubscriptionState.class, KafkaConsumerInfo.class)
              .get(subscriptionState);
      Metadata consumerMetadata = kafkaConsumerInfo.getClientMetadata();
      String clusterId = null;
      if (consumerMetadata != null) {
        clusterId = InstrumentationContext.get(Metadata.class, String.class).get(consumerMetadata);
      }
      LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
      if (clusterId != null) {
        sortedTags.put(KAFKA_CLUSTER_ID_TAG, clusterId);
      }
      sortedTags.put(PARTITION_TAG, String.valueOf(partition.partition()));
      sortedTags.put(TOPIC_TAG, partition.topic());
      sortedTags.put(TYPE_TAG, "kafka_high_watermark");
      AgentTracer.get().getDataStreamsMonitoring().trackBacklog(sortedTags, highWatermark);
    }
  }
}
