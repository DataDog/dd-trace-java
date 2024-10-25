package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker;

public class OffsetCommitCallbackInvokerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnqueue(
      @Advice.Argument(value = 0, readOnly = false) OffsetCommitCallback callback,
      @Advice.This OffsetCommitCallbackInvoker callbackInvoker) {
    KafkaConsumerInfo kafkaConsumerInfo =
        InstrumentationContext.get(OffsetCommitCallbackInvoker.class, KafkaConsumerInfo.class)
            .get(callbackInvoker);
    callback = new DDOffsetCommitCallback(callback, kafkaConsumerInfo);
  }
}
