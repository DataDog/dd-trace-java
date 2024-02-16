package datadog.trace.instrumentation.java.lang.invoke;

import static java.lang.invoke.MethodType.methodType;
import static java.lang.invoke.StringConcatFactory.makeConcatWithConstants;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.StringModule;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
@Propagation
@CallSite(
    spi = IastCallSites.class,
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isMajorJavaVersionAtLeast", "9"})
public class StringConcatFactoryCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(StringConcatFactoryCallSite.class);

  private static final char TAG_ARG = '\u0001';
  private static final char TAG_CONST = '\u0002';
  private static final int NULL_STR_LENGTH = "null".length();
  private static final MethodHandle INSTRUMENTATION_BRIDGE = instrumentationBridgeMethod();

  @CallSite.Around(
      value =
          "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
      invokeDynamic = true)
  public static java.lang.invoke.CallSite aroundMakeConcatWithConstants(
      @CallSite.Argument final MethodHandles.Lookup lookup,
      @CallSite.Argument final String name,
      @CallSite.Argument final MethodType concatType,
      @CallSite.Argument final String recipe,
      @CallSite.Argument final Object... constants)
      throws StringConcatException {
    if (INSTRUMENTATION_BRIDGE == null) {
      return makeConcatWithConstants(lookup, name, concatType, recipe, constants);
    }
    try {
      final MethodHandle[] toStringMethods = new MethodHandle[concatType.parameterCount()];
      final MethodType stringConcatType = lookupToStringConverters(concatType, toStringMethods);
      java.lang.invoke.CallSite callSite =
          makeConcatWithConstants(lookup, name, stringConcatType, recipe, constants);
      if (!(callSite instanceof ConstantCallSite)) {
        // should not happen but better be prepared
        throw new IllegalArgumentException(
            "Expected ConstantCallSite, received " + callSite.getClass());
      }
      MethodHandle target =
          MethodHandles.insertArguments(
              INSTRUMENTATION_BRIDGE, 2, recipe, constants, preprocessRecipe(recipe, constants));
      target = target.asCollector(1, String[].class, concatType.parameterCount());
      target = MethodHandles.foldArguments(target, callSite.getTarget());
      target = MethodHandles.filterArguments(target, 0, toStringMethods);
      return new ConstantCallSite(target);
    } catch (Throwable e) {
      LOG.error(
          "Failed to instrument makeConcatWithConstants, reverting to default concat logic", e);
      return makeConcatWithConstants(lookup, name, concatType, recipe, constants);
    }
  }

  public static String onStringConcatFactory(
      final String result,
      final String[] arguments,
      final String recipe,
      final Object[] constants,
      final int[] recipeOffsets) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null) {
      try {
        module.onStringConcatFactory(result, arguments, recipe, constants, recipeOffsets);
      } catch (final Throwable t) {
        module.onUnexpectedException("Callback for onStringConcatFactory threw.", t);
      }
    }
    return result;
  }

  /**
   * Preprocess the recipe and create an array of offsets where for each offset:
   *
   * <ul>
   *   <li><code>offset < 0</code> length of the current recipe chunk with sign changed
   *   <li><code>offset >= 0</code> index of the argument
   * </ul>
   */
  private static int[] preprocessRecipe(final String recipe, final Object[] constants) {
    final List<Integer> offsets = new ArrayList<>();
    final char[] chars = recipe.toCharArray();
    int count = 0, argIndex = 0, constIndex = 0;
    for (final char value : chars) {
      switch (value) {
        case TAG_ARG:
          if (count > 0) {
            offsets.add(-count);
            count = 0;
          }
          offsets.add(argIndex);
          argIndex++;
          break;
        case TAG_CONST:
          final String constant = getConstant(constants, constIndex);
          constIndex++;
          count += getToStringLength(constant);
          break;
        default:
          count++;
          break;
      }
    }
    if (count > 0) {
      offsets.add(-count);
    }
    final int[] result = new int[offsets.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = offsets.get(i);
    }
    return result;
  }

  private static String getConstant(@Nullable final Object[] constants, final int index) {
    if (constants == null) {
      return "";
    }
    final Object result = constants[index];
    return result instanceof String ? (String) result : result.toString();
  }

  private static int getToStringLength(@Nullable final String s) {
    return s == null ? NULL_STR_LENGTH : s.length();
  }

  private static MethodType lookupToStringConverters(
      final MethodType concatType, final MethodHandle[] toStringMethods) {
    MethodType result = concatType;
    for (int i = 0; i < result.parameterCount(); i++) {
      final Class<?> type = result.parameterType(i);
      toStringMethods[i] = toStringConverterFor(type);
      result = result.changeParameterType(i, String.class);
    }
    return result;
  }

  private static MethodHandle toStringConverterFor(final Class<?> cl) {
    try {
      final MethodType methodType;
      if (cl.isPrimitive()) {
        final Class<?> target = cl == byte.class || cl == short.class ? int.class : cl;
        methodType = methodType(String.class, target);
      } else {
        methodType = methodType(String.class, Object.class);
      }
      final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      final MethodHandle handle = lookup.findStatic(String.class, "valueOf", methodType);
      return methodType.parameterType(0) == cl
          ? handle
          : handle.asType(methodType(String.class, cl));
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch string converter for " + cl, e);
    }
  }

  private static MethodHandle instrumentationBridgeMethod() {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      return lookup.findStatic(
          StringConcatFactoryCallSite.class,
          "onStringConcatFactory",
          methodType(
              String.class,
              String.class,
              String[].class,
              String.class,
              Object[].class,
              int[].class));
    } catch (Throwable e) {
      LOG.error(
          "Failed to fetch instrumentation bridge method handle, no invocations will be instrumented",
          e);
      return null;
    }
  }
}
