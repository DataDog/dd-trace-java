package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.common.TopicPartition;

/** This instrumentation saves the co */
@AutoService(Instrumenter.class)
public final class ConsumerCoordinatorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ConsumerCoordinatorInstrumentation() {
    super("kafka");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    // for each ConsumerCoordinator, we store the consumer group
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
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void trackCommitOffset(
        @Advice.This ConsumerCoordinator coordinator,
        @Advice.Argument(0) final Map<TopicPartition, OffsetAndMetadata> offsets) {
      String consumerGroup =
          InstrumentationContext.get(ConsumerCoordinator.class, String.class).get(coordinator);
      System.out.printf("___________________________________\n");
      System.out.printf("committing offsets %s\n", consumerGroup);
      for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
        AgentTracer.get()
            .trackKafkaCommit(
                consumerGroup,
                entry.getKey().topic(),
                entry.getKey().partition(),
                entry.getValue().offset());
        System.out.printf(
            "partition %d, topic %s, offset %d\n",
            entry.getKey().partition(), entry.getKey().topic(), entry.getValue().offset());
      }
    }
  }
}
