package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/**
 * Detect blocking for older Hikari versions before commit f0b3c520c (<2.6.0) by looking for calls
 * to <code>synchronizer.waitUntilSequenceExceeded(startSeq, timeout)</code>.
 */
@AutoService(InstrumenterModule.class)
public final class HikariQueuedSequenceSynchronizerInstrumentation
    extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HikariQueuedSequenceSynchronizerInstrumentation() {
    super("jdbc", "hikari");
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.util.QueuedSequenceSynchronizer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HikariBlockedTracker",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("waitUntilSequenceExceeded"),
        HikariQueuedSequenceSynchronizerInstrumentation.class.getName()
            + "$WaitUntilSequenceExceededAdvice");
  }

  public static class WaitUntilSequenceExceededAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      HikariBlockedTracker.setBlocked();
    }
  }
}
