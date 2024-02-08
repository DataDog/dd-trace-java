package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringSubsequenceBenchmark
    extends AbstractBenchmark<StringSubsequenceBenchmark.Context> {

  private static final String DEFAULT_STRING = "0123456789";
  private static final int BEGIN_INDEX = 2;
  private static final int END_INDEX = 8;
  private static final int RANGE_SIZE = 2;

  @Override
  protected StringSubsequenceBenchmark.Context initializeContext() {
    final IastRequestContext iastRequestContext = new IastRequestContext();
    final String notTainted = new String(DEFAULT_STRING);

    final String taintedLoseRange = new String(DEFAULT_STRING);
    iastRequestContext
        .getTaintedObjects()
        .taint(
            taintedLoseRange,
            new Range[] {
              new Range(0, RANGE_SIZE, new Source((byte) 0, "key", "value"), NOT_MARKED)
            });

    final String taintedModifyRange = new String(DEFAULT_STRING);
    iastRequestContext
        .getTaintedObjects()
        .taint(
            taintedModifyRange,
            new Range[] {
              new Range(1, RANGE_SIZE, new Source((byte) 1, "key", "value"), NOT_MARKED)
            });

    return new StringSubsequenceBenchmark.Context(
        iastRequestContext, notTainted, taintedLoseRange, taintedModifyRange);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public CharSequence baseline() {
    final String self = context.notTainted;
    return self.subSequence(BEGIN_INDEX, END_INDEX);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public CharSequence iastDisabled() {
    return instrumentStringSubsequence(context.notTainted);
  }

  /** For a tainted String with one range subsequence returns a CharSequence without ranges */
  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public CharSequence taintedLoseRange() {
    return instrumentStringSubsequence(context.taintedLoseRange);
  }

  /**
   * For a tainted String with one range subsequence returns a CharSequence with a modified range
   * (changes offset and length)
   */
  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public CharSequence taintedModifyRange() {
    return instrumentStringSubsequence(context.taintedModifyRange);
  }

  private CharSequence instrumentStringSubsequence(final String self) {
    final CharSequence result = self.subSequence(BEGIN_INDEX, END_INDEX);
    InstrumentationBridge.STRING.onStringSubSequence(self, BEGIN_INDEX, END_INDEX, result);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {
    private final String notTainted;

    private final String taintedLoseRange;

    private final String taintedModifyRange;

    protected Context(
        final IastContext iastContext,
        final String notTainted,
        final String taintedLoseRange,
        final String taintedModifyRange) {
      super(iastContext);
      this.notTainted = notTainted;
      this.taintedLoseRange = taintedLoseRange;
      this.taintedModifyRange = taintedModifyRange;
    }
  }
}
