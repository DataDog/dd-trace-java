package com.datadog.iast.propagation;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Range;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationValue;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.InvokeDynamic;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.JavaType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;

@OutputTimeUnit(MICROSECONDS)
public class StringConcatFactoryBatchBenchmark
    extends AbstractBenchmark<StringConcatFactoryBatchBenchmark.Context> {

  private static final Class<?> CONCAT_IMPL = buildConcatImplementation(10, 100);
  private static final Map<Integer, MethodHandle> CONCAT_METHODS = resolveConcatMethods(10, 100);

  @Param({"10", "100"})
  public int stringCount;

  @Param({"0", "50", "100"})
  public int taintedPct;

  @Override
  protected StringConcatFactoryBatchBenchmark.Context initializeContext() {
    final IastRequestContext context = new IastRequestContext();
    final String[] values = new String[stringCount];
    final double limit = taintedPct / 100D;
    for (int i = 0; i < stringCount; i++) {
      double current = i / (double) stringCount;
      final String value;
      if (current < limit) {
        value = tainted(context, "Yep, tainted", new Range(3, 5, source(), NOT_MARKED));
      } else {
        value = notTainted("Nop, tainted");
      }
      values[i] = value;
    }
    return new StringConcatFactoryBatchBenchmark.Context(context, values, stringCount);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String baseline() throws Throwable {
    return (String) context.method.invokeWithArguments(context.strings);
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=false"})
  public String iastDisabled() throws Throwable {
    final String result = (String) context.method.invokeWithArguments(context.strings);
    InstrumentationBridge.STRING.onStringConcatFactory(
        result, context.stringArray, context.recipe, context.constants, context.recipeOffsets);
    return result;
  }

  @Benchmark
  @Fork(jvmArgsAppend = {"-Ddd.iast.enabled=true"})
  public String iastEnabled() throws Throwable {
    final String result = (String) context.method.invokeWithArguments(context.strings);
    InstrumentationBridge.STRING.onStringConcatFactory(
        result, context.stringArray, context.recipe, context.constants, context.recipeOffsets);
    return result;
  }

  protected static class Context extends AbstractBenchmark.BenchmarkContext {

    private final List<String> strings;
    private final String[] stringArray;
    private final String recipe;
    private final int[] recipeOffsets;
    private final Object[] constants;
    private final MethodHandle method;

    protected Context(final IastContext iasContext, final String[] strings, final int paramCount) {
      super(iasContext);
      this.strings = Arrays.asList(strings);
      stringArray = strings;
      recipe = buildRecipe(paramCount);
      recipeOffsets = buildRecipeOffsets(paramCount);
      constants = new Object[0];
      method = CONCAT_METHODS.get(paramCount);
    }
  }

  private static String buildRecipe(final int paramCount) {
    return IntStream.range(0, paramCount)
        .mapToObj(i -> "\u0001")
        .collect(Collectors.joining(" + "));
  }

  private static int[] buildRecipeOffsets(final int arity) {
    return IntStream.range(0, arity)
        .mapToObj(i -> Arrays.asList(i, -3))
        .flatMap(Collection::stream)
        .mapToInt(i -> i)
        .toArray();
  }

  private static Class<?> buildConcatImplementation(final int... arity) {
    try {
      final String packageName = StringConcatFactoryBatchBenchmark.class.getPackage().getName();
      DynamicType.Builder<?> builder =
          new ByteBuddy()
              .subclass(Object.class)
              .name(packageName + ".StringConcatFactoryImplementor");
      for (final int paramCount : arity) {
        builder =
            builder
                .defineMethod("concat", String.class, Visibility.PUBLIC, Ownership.STATIC)
                .withParameters(
                    IntStream.range(0, paramCount)
                        .mapToObj(i -> String.class)
                        .collect(Collectors.toList()))
                .intercept(
                    InvokeDynamic.bootstrap(
                            makeConcatWithConstantsDescriptor(), buildRecipe(paramCount))
                        .invoke("makeConcatWithConstants")
                        .withImplicitAndMethodArguments());
      }
      return builder
          .make()
          .load(StringConcatFactoryBatchBenchmark.class.getClassLoader())
          .getLoaded();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static MethodDescription.Latent makeConcatWithConstantsDescriptor() {
    TypeDescription OBJECT = TypeDescription.ForLoadedType.of(Object.class);
    TypeDescription STRING = TypeDescription.ForLoadedType.of(String.class);
    return new MethodDescription.Latent(
        new TypeDescription.Latent(
            "java.lang.invoke.StringConcatFactory", Opcodes.ACC_PUBLIC, OBJECT.asGenericType()),
        "makeConcatWithConstants",
        Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
        Collections.emptyList(),
        JavaType.CALL_SITE.getTypeStub().asGenericType(),
        Arrays.asList(
            new ParameterDescription.Token(
                JavaType.METHOD_HANDLES_LOOKUP.getTypeStub().asGenericType()),
            new ParameterDescription.Token(STRING.asGenericType()),
            new ParameterDescription.Token(JavaType.METHOD_TYPE.getTypeStub().asGenericType()),
            new ParameterDescription.Token(STRING.asGenericType()),
            new ParameterDescription.Token(
                TypeDescription.Generic.Builder.of(OBJECT.asGenericType()).asArray().build())),
        Collections.emptyList(),
        Collections.emptyList(),
        AnnotationValue.UNDEFINED,
        TypeDescription.Generic.UNDEFINED);
  }

  private static Map<Integer, MethodHandle> resolveConcatMethods(final int... arity) {
    try {
      final Map<Integer, MethodHandle> result = new HashMap<>();
      for (final int paramCount : arity) {
        final MethodHandle method =
            MethodHandles.publicLookup()
                .findStatic(
                    StringConcatFactoryBatchBenchmark.CONCAT_IMPL,
                    "concat",
                    MethodType.methodType(
                        String.class,
                        IntStream.range(0, paramCount)
                            .mapToObj(i -> String.class)
                            .collect(Collectors.toList())));
        result.put(paramCount, method);
      }
      return result;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
