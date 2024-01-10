package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.instrumentation.java.lang.StringCallSite;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringConcatBenchmark extends AbstractBenchmark<StringConcatBenchmark.Context> {

  @Override
  protected StringConcatBenchmark.Context initializeContext() {
    final IastRequestContext context = new IastRequestContext();
    final String notTainted = notTainted("I am not a tainted string");
    final String tainted =
        tainted(context, "I am a tainted string", new Range(3, 5, source(), NOT_MARKED));
    return new StringConcatBenchmark.Context(context, notTainted, tainted);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() {
    final String self = context.notTainted;
    final String param = context.notTainted;
    return self.concat(param);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() {
    final String self = context.notTainted;
    final String param = context.notTainted;
    final String result = self.concat(param);
    StringCallSite.afterConcat(self, param, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String notTainted() {
    final String self = context.notTainted;
    final String param = context.notTainted;
    final String result = self.concat(param);
    StringCallSite.afterConcat(self, param, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String paramTainted() {
    final String self = context.notTainted;
    final String param = context.tainted;
    final String result = self.concat(param);
    StringCallSite.afterConcat(self, param, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String stringTainted() {
    final String self = context.tainted;
    final String param = context.notTainted;
    final String result = self.concat(param);
    StringCallSite.afterConcat(self, param, result);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String bothTainted() {
    final String self = context.tainted;
    final String param = context.tainted;
    final String result = self.concat(param);
    StringCallSite.afterConcat(self, param, result);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;

    protected Context(final IastContext context, final String notTainted, final String tainted) {
      super(context);
      this.notTainted = notTainted;
      this.tainted = tainted;
    }
  }
}
