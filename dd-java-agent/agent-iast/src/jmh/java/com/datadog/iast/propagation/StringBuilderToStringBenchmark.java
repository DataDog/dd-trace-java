package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.instrumentation.java.lang.StringBuilderCallSite;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringBuilderToStringBenchmark
    extends AbstractBenchmark<StringBuilderToStringBenchmark.Context> {

  @Override
  protected Context initializeContext() {
    final IastRequestContext context = new IastRequestContext();
    final StringBuilder notTaintedBuilder =
        notTainted(new StringBuilder("I am not a tainted string builder"));
    final StringBuilder taintedBuilder =
        tainted(
            context,
            new StringBuilder("I am a tainted string builder"),
            new Range(5, 7, source(), NOT_MARKED));
    return new Context(context, notTaintedBuilder, taintedBuilder);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() {
    return context.notTaintedBuilder.toString();
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() {
    final StringBuilder self = context.notTaintedBuilder;
    final String result = self.toString();
    StringBuilderCallSite.afterToString(self, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String notTainted() {
    final StringBuilder self = context.notTaintedBuilder;
    final String result = self.toString();
    StringBuilderCallSite.afterToString(self, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String tainted() {
    final StringBuilder self = context.taintedBuilder;
    final String result = self.toString();
    StringBuilderCallSite.afterToString(self, result);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final StringBuilder notTaintedBuilder;

    private final StringBuilder taintedBuilder;

    protected Context(
        final IastContext context,
        final StringBuilder notTaintedBuilder,
        final StringBuilder taintedBuilder) {
      super(context);
      this.notTaintedBuilder = notTaintedBuilder;
      this.taintedBuilder = taintedBuilder;
    }
  }
}
