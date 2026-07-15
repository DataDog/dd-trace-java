package datadog.trace.instrumentation.karate2;

import io.karatelabs.core.RunEvent;
import io.karatelabs.core.RunListener;
import io.karatelabs.core.Runner;
import net.bytebuddy.asm.Advice;

/** Advice for the {@code io.karatelabs.core.Runner.Builder} constructor. */
public class KarateBuilderAdvice {

  @Advice.OnMethodExit
  public static void onRunnerBuilderConstructorExit(@Advice.This Runner.Builder builder) {
    builder.listener(new KarateTracingListener());
  }

  // Karate 2.0.0 and above
  public static void muzzleCheck(RunListener runListener) {
    runListener.onEvent((RunEvent) null);
  }
}
