package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.core.datastreams.TagsProcessor.createTag;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.clients.consumer.internals.RequestFuture;
import org.apache.kafka.common.TopicPartition;

@AutoService(Instrumenter.class)
public final class ConsumerCoordinatorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ConsumerCoordinatorInstrumentation() {
    super("kafka");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put(
        "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator", "java.lang.String");
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.ConsumerCoordinator";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("sendOffsetCommitRequest")).and(takesArguments(1)),
        ConsumerCoordinatorInstrumentation.class.getName() + "$CommitOffsetAdvice");
  }

  public static class CommitOffsetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void trackCommitOffset(
        @Advice.This ConsumerCoordinator coordinator,
        @Advice.Return RequestFuture<Void> requestFuture,
        @Advice.Argument(0) final Map<TopicPartition, OffsetAndMetadata> offsets) {
      if (requestFuture.failed()) {
        return;
      }
      String consumerGroup =
          InstrumentationContext.get(ConsumerCoordinator.class, String.class).get(coordinator);
      for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
        if (consumerGroup == null) {
          consumerGroup = "";
        }
        List<String> sortedTags =
            Arrays.asList(
                createTag("consumer_group", consumerGroup),
                createTag("partition", String.valueOf(entry.getKey().partition())),
                createTag("topic", entry.getKey().topic()),
                "type:kafka_commit");
        AgentTracer.get()
            .getDataStreamsMonitoring()
            .trackBacklog(sortedTags, entry.getValue().offset());
      }
    }

    public static void muzzleCheck(ConsumerRecord record) {
      // KafkaConsumerInstrumentation only applies for kafka versions with headers
      // Make an explicit call so ConsumerCoordinatorInstrumentation does the same
      record.headers();
    }
  }
}
