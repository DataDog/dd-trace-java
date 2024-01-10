package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.instrumentation.java.lang.StringBuilderCallSite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;

@OutputTimeUnit(MICROSECONDS)
public class StringBuilderBatchBenchmark
    extends AbstractBenchmark<StringBuilderBatchBenchmark.Context> {

  @Param({"10", "100"})
  public int stringCount;

  @Param({"0", "50", "100"})
  public int taintedPct;

  @Override
  protected StringBuilderBatchBenchmark.Context initializeContext() {
    final IastRequestContext context = new IastRequestContext();
    final List<String> values = new ArrayList<>(stringCount);
    final double limit = taintedPct / 100D;
    for (int i = 0; i < stringCount; i++) {
      double current = i / (double) stringCount;
      final String value;
      if (current < limit) {
        value =
            tainted(context, UUID.randomUUID().toString(), new Range(3, 6, source(), NOT_MARKED));
      } else {
        value = notTainted(UUID.randomUUID().toString());
      }
      values.add(value);
    }
    Collections.shuffle(values);
    return new StringBuilderBatchBenchmark.Context(context, values);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() {
    final StringBuilder builder = new StringBuilder();
    for (final String string : context.strings) {
      builder.append(string);
    }
    return builder.toString();
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() {
    final StringBuilder builder = new StringBuilder();
    for (final String string : context.strings) {
      builder.append(string);
      StringBuilderCallSite.afterAppend(builder, string, builder);
    }
    final String result = builder.toString();
    StringBuilderCallSite.afterToString(builder, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String iastEnabled() {
    final StringBuilder builder = new StringBuilder();
    for (final String string : context.strings) {
      builder.append(string);
      StringBuilderCallSite.afterAppend(builder, string, builder);
    }
    final String result = builder.toString();
    StringBuilderCallSite.afterToString(builder, result);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final List<String> strings;

    protected Context(final IastContext context, final List<String> strings) {
      super(context);
      this.strings = strings;
    }
  }
}
