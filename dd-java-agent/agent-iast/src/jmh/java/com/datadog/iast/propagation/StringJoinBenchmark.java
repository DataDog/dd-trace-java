package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringJoinBenchmark extends AbstractBenchmark<StringJoinBenchmark.Context> {
  @Override
  protected StringJoinBenchmark.Context initializeContext() {
    final String notTainted = new String("I am not a tainted string");
    final String notTaintedDelimiter = new String("-");
    final IastRequestContext iastRequestContext = new IastRequestContext();

    final String tainted = new String("I am a tainted string");
    iastRequestContext
        .getTaintedObjects()
        .taint(
            tainted,
            new Range[] {
              new Range(0, tainted.length(), new Source((byte) 0, "key", "value"), NOT_MARKED)
            });

    final String taintedDelimiter = new String("-");
    iastRequestContext
        .getTaintedObjects()
        .taint(
            taintedDelimiter,
            new Range[] {
              new Range(
                  0, taintedDelimiter.length(), new Source((byte) 1, "key", "value"), NOT_MARKED)
            });

    return new StringJoinBenchmark.Context(
        iastRequestContext, notTainted, tainted, notTaintedDelimiter, taintedDelimiter);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() {
    final String delimiter = context.noTaintedDelimiter;
    final String element = context.notTainted;
    return String.join(delimiter, element, element, element, element, element);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() {
    final String delimiter = context.noTaintedDelimiter;
    final String element = context.notTainted;
    return instrumentStringJoin(delimiter, element);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String notTainted() {
    final String delimiter = context.noTaintedDelimiter;
    final String element = context.notTainted;
    return instrumentStringJoin(delimiter, element);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String tainted() {
    final String delimiter = context.taintedDelimiter;
    final String element = context.tainted;
    return instrumentStringJoin(delimiter, element);
  }

  private static String instrumentStringJoin(final String delimiter, final String element) {
    final String result = String.join(delimiter, element, element, element, element, element);
    InstrumentationBridge.STRING.onStringJoin(
        result, delimiter, new String[] {element, element, element, element, element});
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;
    private final String taintedDelimiter;
    private final String noTaintedDelimiter;

    protected Context(
        final IastContext iasContext,
        final String notTainted,
        final String tainted,
        final String noTaintedDelimiter,
        final String taintedDelimiter) {
      super(iasContext);
      this.notTainted = notTainted;
      this.tainted = tainted;
      this.noTaintedDelimiter = noTaintedDelimiter;
      this.taintedDelimiter = taintedDelimiter;
    }
  }
}
