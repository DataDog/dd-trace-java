package datadog.trace.instrumentation.kafka_clients38;

import java.lang.invoke.MethodHandles;
import net.bytebuddy.asm.Advice;

public final class RecordHeaderConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit() {
    if (RecordHeaderAccess.needsInitialization()) {
      // Keep the caller-sensitive lookup inlined into Kafka's constructor.
      RecordHeaderAccess.initialize(MethodHandles.lookup());
    }
  }
}
