package datadog.trace.instrumentation.kafka_clients;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.internals.ConsumerCoordinator;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.TopicPartition;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public final class SubscriptionStateInstrumentation2 extends Instrumenter.Tracing implements Instrumenter.ForSingleType {
  public SubscriptionStateInstrumentation2() {
    super("kafka");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.kafka.clients.consumer.internals.SubscriptionState";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("updateHighWatermark")).and(takesArguments(2)),
        SubscriptionStateInstrumentation2.class.getName() + "$FetcherAdvice");
  }

  public static class FetcherAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void trackRecordLag(
        @Advice.This SubscriptionState subscriptionState,
        @Advice.Argument(0) TopicPartition partition,
        @Advice.Argument(1) long highWatermark
        ) {
      System.out.println("high watermark " + highWatermark + partition.toString());
      System.out.println("position is " + subscriptionState.position(partition));
      System.out.println("lag is " + (highWatermark - subscriptionState.position(partition).offset));
    }
  }
}
