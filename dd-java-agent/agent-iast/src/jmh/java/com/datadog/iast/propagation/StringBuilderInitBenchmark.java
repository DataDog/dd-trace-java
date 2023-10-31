package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import datadog.trace.instrumentation.java.lang.StringBuilderCallSite;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringBuilderInitBenchmark
    extends AbstractBenchmark<StringBuilderInitBenchmark.Context> {

  @Override
  protected Context initializeContext() {
    final String notTainted = notTainted("I am not a tainted string");
    final String tainted = tainted("I am a tainted string", new Range(3, 6, source(), NOT_MARKED));
    return new Context(notTainted, tainted);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public StringBuilder baseline() {
    return new StringBuilder(context.notTainted);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public StringBuilder iastDisabled() {
    final String param = context.notTainted;
    final StringBuilder self = new StringBuilder(param);
    StringBuilderCallSite.afterInit(new Object[] {param}, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder notTainted() {
    final String param = context.notTainted;
    final StringBuilder self = new StringBuilder(param);
    StringBuilderCallSite.afterInit(new Object[] {param}, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder tainted() {
    final String param = context.tainted;
    final StringBuilder self = new StringBuilder(param);
    StringBuilderCallSite.afterInit(new Object[] {param}, self);
    return self;
  }

  protected static class Context implements AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;

    protected Context(final String notTainted, final String tainted) {
      this.tainted = tainted;
      this.notTainted = notTainted;
    }
  }
}
