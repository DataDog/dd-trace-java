package datadog.trace.bootstrap.instrumentation.ffm;

import datadog.context.ContextScope;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FFMNativeMethodDecorator extends BaseDecorator {
  private static final Logger LOGGER = LoggerFactory.getLogger(FFMNativeMethodDecorator.class);
  private static final CharSequence TRACE_FFM = UTF8BytesString.create("trace-ffm");
  private static final CharSequence OPERATION_NAME = UTF8BytesString.create("trace.native");

  private static final MethodHandle START_SPAN_MH =
      safeFindStatic(
          "startSpan",
          MethodType.methodType(ContextScope.class, CharSequence.class, boolean.class));
  private static final MethodHandle END_SPAN_MH =
      safeFindStatic(
          "endSpan",
          MethodType.methodType(Object.class, Throwable.class, ContextScope.class, Object.class));

  public static final FFMNativeMethodDecorator DECORATE = new FFMNativeMethodDecorator();

  private static MethodHandle safeFindStatic(String name, MethodType methodType) {
    try {
      return MethodHandles.lookup().findStatic(FFMNativeMethodDecorator.class, name, methodType);
    } catch (Throwable t) {
      LOGGER.debug("Cannot find method {} in NativeMethodHandleWrapper", name, t);
      return null;
    }
  }

  public static MethodHandle wrap(
      final MethodHandle original, final String libraryName, final String methodName) {
    if (START_SPAN_MH == null || END_SPAN_MH == null) {
      return original;
    }
    try {
      MethodType originalType = original.type();
      boolean isVoid = originalType.returnType() == void.class;

      // We need the ContextScope to be visible to the finally block.
      // Easiest way is to artificially prepend it to the target signature.
      // The added parameter is ignored by the original handle.
      // originalWithScope: (ContextScope, args...) -> R
      MethodHandle originalWithScope = MethodHandles.dropArguments(original, 0, ContextScope.class);

      /*
       * Build the cleanup handle used by MethodHandles.tryFinally.
       *
       * tryFinally has a strict calling convention:
       *   - void target  -> cleanup(Throwable, ContextScope, args...)
       *   - non-void     -> cleanup(Throwable, R, ContextScope, args...)
       *
       * END_SPAN_MH is (Throwable, ContextScope, Object) -> Object,
       * so we need to reshape it to match what tryFinally expects.
       */
      MethodHandle cleanup;

      if (isVoid) {
        // No return value: bind `null` as the result argument.
        MethodHandle endWithNull = MethodHandles.insertArguments(END_SPAN_MH, 2, (Object) null);

        // Make it accept the original arguments even though they are unused.
        MethodHandle endDropped =
            MethodHandles.dropArguments(endWithNull, 2, originalType.parameterList());

        // tryFinally requires void return for void targets.
        cleanup = endDropped.asType(endDropped.type().changeReturnType(void.class));

      } else {
        /*
         * Non-void case:
         * tryFinally will call cleanup as:
         *   (Throwable, returnValue, ContextScope, args...)
         *
         * END_SPAN_MH expects:
         *   (Throwable, ContextScope, result)
         *
         * So we first permute parameters to swap returnValue and ContextScope.
         */
        MethodHandle endPermuted =
            MethodHandles.permuteArguments(
                END_SPAN_MH,
                MethodType.methodType(
                    Object.class, Throwable.class, Object.class, ContextScope.class),
                0,
                2,
                1);

        // Accept original arguments (unused) after the required ones.
        MethodHandle endDropped =
            MethodHandles.dropArguments(endPermuted, 3, originalType.parameterList());

        // Adapt return and result parameter types to match the original signature.
        MethodType cleanupType =
            endDropped
                .type()
                .changeParameterType(1, originalType.returnType())
                .changeReturnType(originalType.returnType());

        cleanup = endDropped.asType(cleanupType);
      }

      // Wrap the original in try/finally semantics.
      // Resulting handle:
      //   (ContextScope, args...) -> R
      MethodHandle withFinally = MethodHandles.tryFinally(originalWithScope, cleanup);

      // Precompute span metadata so we don't redo the lookup per invocation.
      final CharSequence resourceName = resourceNameFor(libraryName, methodName);
      final boolean methodMeasured = isMethodMeasured(libraryName, methodName);

      // Bind both arguments to startSpan.
      // After binding: () -> ContextScope
      MethodHandle boundStart =
          MethodHandles.insertArguments(START_SPAN_MH, 0, resourceName, methodMeasured);

      // Make it look like it takes the same arguments as the original,
      // even though they are ignored.
      // (args...) -> ContextScope
      MethodHandle startCombiner =
          MethodHandles.dropArguments(boundStart, 0, originalType.parameterList());

      /*
       * foldArguments wires it all together:
       *
       *   scope = startCombiner(args...)
       *   return withFinally(scope, args...)
       *
       * Final shape matches the original:
       *   (args...) -> R
       */
      return MethodHandles.foldArguments(withFinally, startCombiner);

    } catch (Throwable t) {
      LOGGER.debug(
          "Cannot wrap method handle for library {} and method {}", libraryName, methodName, t);
      return original;
    }
  }

  @SuppressWarnings("unused")
  public static ContextScope startSpan(CharSequence resourceName, boolean methodMeasured) {
    AgentSpan span = AgentTracer.startSpan(TRACE_FFM.toString(), OPERATION_NAME);
    DECORATE.afterStart(span);
    span.setResourceName(resourceName);
    return AgentTracer.activateSpan(span);
  }

  @SuppressWarnings("unused")
  public static Object endSpan(Throwable t, ContextScope scope, Object result) {
    try {
      if (scope != null) {
        final AgentSpan span = AgentSpan.fromContext(scope.context());
        scope.close();

        if (span != null) {
          if (t != null) {
            DECORATE.onError(span, t);
            span.addThrowable(t);
          }

          span.finish();
        }
      }
    } catch (Throwable ignored) {

    }
    return result;
  }

  public static boolean isMethodTraced(final String library, final String method) {
    return matches(InstrumenterConfig.get().getTraceNativeMethods().get(library), method);
  }

  public static boolean isMethodMeasured(final String library, final String method) {
    return matches(InstrumenterConfig.get().getMeasureMethods().get(library), method);
  }

  public static CharSequence resourceNameFor(final String library, final String method) {
    if (library == null || library.isEmpty()) {
      return UTF8BytesString.create(method);
    }
    return UTF8BytesString.create(library + "." + method);
  }

  private static boolean matches(final Set<String> allows, final String method) {
    if (allows == null) {
      return false;
    }
    return allows.contains(method) || allows.contains("*");
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {TRACE_FFM.toString()};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return TRACE_FFM;
  }
}
