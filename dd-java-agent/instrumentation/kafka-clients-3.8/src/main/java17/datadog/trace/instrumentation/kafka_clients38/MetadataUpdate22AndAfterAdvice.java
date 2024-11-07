package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.internals.OffsetCommitCallbackInvoker;
import org.apache.kafka.common.requests.MetadataResponse;

public class MetadataUpdate22AndAfterAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This final Metadata metadata, @Advice.Argument(1) final MetadataResponse response) {
    if (response != null) {
      InstrumentationContext.get(Metadata.class, String.class).put(metadata, response.clusterId());
    }
  }

  public static void muzzleCheck(OffsetCommitCallbackInvoker invoker) {
    // Only applies for kafka versions with OffsetCommitCallbackInvoker
    invoker.executeCallbacks();
  }
}
