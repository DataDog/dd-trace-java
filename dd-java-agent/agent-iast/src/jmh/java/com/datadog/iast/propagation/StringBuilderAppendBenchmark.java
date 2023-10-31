package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.model.Range;
import datadog.trace.instrumentation.java.lang.StringBuilderCallSite;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringBuilderAppendBenchmark
    extends AbstractBenchmark<StringBuilderAppendBenchmark.Context> {

  @Override
  protected Context initializeContext() {
    final String notTainted = notTainted("I am not a tainted string");
    final String tainted = tainted("I am a tainted string", new Range(5, 6, source(), NOT_MARKED));
    final StringBuilder notTaintedBuilder =
        notTainted(new StringBuilder("I am not a tainted string builder"));
    final StringBuilder taintedBuilder =
        tainted(
            new StringBuilder("I am a tainted string builder"),
            new Range(5, 6, source(), NOT_MARKED));
    return new Context(notTainted, tainted, notTaintedBuilder, taintedBuilder);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public StringBuilder baseline() {
    return context.notTaintedBuilder.append(context.notTainted);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public StringBuilder iastDisabled() {
    final String param = context.notTainted;
    final StringBuilder self = context.notTaintedBuilder.append(param);
    StringBuilderCallSite.afterAppend(self, param, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder notTainted() {
    final String param = context.notTainted;
    final StringBuilder self = context.notTaintedBuilder.append(param);
    StringBuilderCallSite.afterAppend(self, param, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder paramTainted() {
    final String param = context.tainted;
    final StringBuilder self = context.notTaintedBuilder.append(param);
    StringBuilderCallSite.afterAppend(self, param, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder stringBuilderTainted() {
    final String param = context.notTainted;
    final StringBuilder self = context.taintedBuilder.append(param);
    StringBuilderCallSite.afterAppend(self, param, self);
    return self;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public StringBuilder bothTainted() {
    final String param = context.tainted;
    final StringBuilder self = context.taintedBuilder.append(param);
    StringBuilderCallSite.afterAppend(self, param, self);
    return self;
  }

  protected static class Context implements AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;

    private final StringBuilder notTaintedBuilder;

    private final StringBuilder taintedBuilder;

    protected Context(
        final String notTainted,
        final String tainted,
        final StringBuilder notTaintedBuilder,
        final StringBuilder taintedBuilder) {
      this.tainted = tainted;
      this.notTainted = notTainted;
      this.notTaintedBuilder = notTaintedBuilder;
      this.taintedBuilder = taintedBuilder;
    }
  }
}
