package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.instrumentation.java.lang.StringBuilderCallSite;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringBuilderInitBenchmark
    extends AbstractBenchmark<StringBuilderInitBenchmark.Context> {

  @Override
  protected Context initializeContext() {
    final IastRequestContext context = new IastRequestContext();
    final String notTainted = notTainted("I am not a tainted string");
    final String tainted =
        tainted(context, "I am a tainted string", new Range(3, 6, source(), NOT_MARKED));
    return new Context(context, notTainted, tainted);
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

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;

    protected Context(final IastContext context, final String notTainted, final String tainted) {
      super(context);
      this.tainted = tainted;
      this.notTainted = notTainted;
    }
  }
}
