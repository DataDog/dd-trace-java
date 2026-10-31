package datadog.trace.core.scopemanager;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE, desc = "The continuation is finalized exactly once.")
@Outcome(expect = FORBIDDEN, desc = "The continuation leaked or was finalized more than once.")
@State
public class ScopeContinuationReleaseResumeStressTest {
  private final RecordingCollector collector = new RecordingCollector();
  private final ScopeContinuation continuation =
      new ScopeContinuation(
              new ContinuableScopeManager(0, false), Context.root(), (byte) 0, collector)
          .register()
          .hold();

  @Actor
  public void release() {
    continuation.release();
  }

  @Actor
  public void resumeAndClose() {
    ContextScope scope = continuation.resume();
    scope.close();
  }

  @Arbiter
  public void observe(I_Result result) {
    result.r1 = collector.removals.get();
  }

  private static final class RecordingCollector implements AgentTraceCollector {
    private final AtomicInteger removals = new AtomicInteger();

    @Override
    public void registerContinuation(ContextContinuation continuation) {}

    @Override
    public void removeContinuation(ContextContinuation continuation) {
      removals.incrementAndGet();
    }
  }
}
