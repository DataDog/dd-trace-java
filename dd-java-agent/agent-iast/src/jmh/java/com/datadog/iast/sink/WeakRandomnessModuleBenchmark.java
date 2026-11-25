package com.datadog.iast.sink;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.datadog.iast.Dependencies;
import com.datadog.iast.Reporter;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operation;
import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of the weak-randomness hook that runs before every call to a {@code Random} or {@code
 * ThreadLocalRandom} method, in its steady state: the per-request quota for reporting is used up
 * (or the request isn't sampled), so nothing is reported.
 */
@Warmup(iterations = 3, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@Fork(3)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class WeakRandomnessModuleBenchmark {

  private WeakRandomnessModuleImpl module;

  @Setup(Level.Trial)
  public void setup() {
    Dependencies dependencies =
        new Dependencies(
            Config.get(),
            new Reporter(Config.get(), null),
            new QuotaExhausted(),
            StackWalkerFactory.INSTANCE,
            new NoContext());
    module = new WeakRandomnessModuleImpl(dependencies);
  }

  /** The call site passes the receiver's class; for ThreadLocalRandom it is always the same. */
  @Benchmark
  public void weakRandom() {
    module.onWeakRandom(ThreadLocalRandom.class);
  }

  /** A secure implementation is skipped before any reporting. */
  @Benchmark
  public void secureRandom() {
    module.onWeakRandom(SecureRandom.class);
  }

  private static final class QuotaExhausted implements OverheadController {
    @Override
    public boolean acquireRequest() {
      return false;
    }

    @Override
    public void reset() {}

    @Override
    public int releaseRequest() {
      return 0;
    }

    @Override
    public boolean hasQuota(Operation operation, @Nullable AgentSpan span) {
      return false;
    }

    @Override
    public boolean consumeQuota(Operation operation, @Nullable AgentSpan span) {
      return false;
    }

    @Override
    public boolean consumeQuota(
        Operation operation, @Nullable AgentSpan span, @Nullable VulnerabilityType type) {
      return false;
    }
  }

  private static final class NoContext extends IastContext.Provider {
    @Nullable
    @Override
    public IastContext resolve() {
      return null;
    }

    @Override
    public IastContext buildRequestContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void releaseRequestContext(@Nonnull IastContext context) {}
  }
}
