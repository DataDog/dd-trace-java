package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;

public class StringConcatFactoryBenchmark
    extends AbstractBenchmark<StringConcatFactoryBenchmark.Context> {

  @Override
  protected StringConcatFactoryBenchmark.Context initializeContext() {
    final IastContext context = new IastRequestContext();
    final String notTainted = notTainted("Nop, tainted");
    final String tainted = tainted(context, "Yep, tainted", new Range(3, 5, source(), NOT_MARKED));
    return new StringConcatFactoryBenchmark.Context(context, notTainted, tainted);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() {
    final String first = context.notTainted;
    final String second = context.notTainted;
    return first + " " + second;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() {
    final String first = context.notTainted;
    final String second = context.notTainted;
    final String result = first + " " + second;
    InstrumentationBridge.STRING.onStringConcatFactory(
        result,
        new String[] {first, second},
        context.recipe,
        context.constants,
        context.recipeOffsets);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String notTainted() {
    final String first = context.notTainted;
    final String second = context.notTainted;
    final String result = first + " " + second;
    InstrumentationBridge.STRING.onStringConcatFactory(
        result,
        new String[] {first, second},
        context.recipe,
        context.constants,
        context.recipeOffsets);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String firstTainted() {
    final String first = context.tainted;
    final String second = context.notTainted;
    final String result = first + " " + second;
    InstrumentationBridge.STRING.onStringConcatFactory(
        result,
        new String[] {first, second},
        context.recipe,
        context.constants,
        context.recipeOffsets);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String secondTainted() {
    final String first = context.notTainted;
    final String second = context.tainted;
    final String result = first + " " + second;
    InstrumentationBridge.STRING.onStringConcatFactory(
        result,
        new String[] {first, second},
        context.recipe,
        context.constants,
        context.recipeOffsets);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String bothTainted() {
    final String first = context.tainted;
    final String second = context.tainted;
    final String result = first + " " + second;
    InstrumentationBridge.STRING.onStringConcatFactory(
        result,
        new String[] {first, second},
        context.recipe,
        context.constants,
        context.recipeOffsets);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final String notTainted;
    private final String tainted;
    private final String recipe;
    private final int[] recipeOffsets;
    private final Object[] constants;

    protected Context(
        final IastContext iastContext, final String notTainted, final String tainted) {
      super(iastContext);
      this.notTainted = notTainted;
      this.tainted = tainted;
      recipe = "\u0001 \u0001";
      constants = new Object[0];
      recipeOffsets = new int[] {0, -1, 1};
    }
  }
}
