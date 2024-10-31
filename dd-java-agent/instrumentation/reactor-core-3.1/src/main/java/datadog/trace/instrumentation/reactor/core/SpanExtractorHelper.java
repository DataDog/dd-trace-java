package datadog.trace.instrumentation.reactor.core;

import datadog.trace.api.GenericClassValue;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.function.Function;
import javax.annotation.Nonnull;

public class SpanExtractorHelper {
  private static final Function<Object, AgentSpan> NULL_SPAN_FUNCTION = ignored -> null;

  private static class CachedDelegateCaller implements Function<Object, AgentSpan> {

    private final MethodHandle getterMethodHandle;

    private CachedDelegateCaller(@Nonnull final MethodHandle getterMethodHandle) {
      this.getterMethodHandle = getterMethodHandle;
    }

    @Override
    public AgentSpan apply(Object mutableSpan) {
      try {
        return (AgentSpan) getterMethodHandle.invoke(mutableSpan);
      } catch (Throwable ignored) {
      }
      return null;
    }
  }

  private static final ClassValue<Function<?, AgentSpan>> DELEGATE_CLASS_VALUE =
      GenericClassValue.of(
          aClass -> {
            if (AgentSpan.class.isAssignableFrom(aClass)) {
              return Function.identity();
            } else if (MutableSpan.class.isAssignableFrom(aClass)
                || "datadog.opentelemetry.shim.trace.OtelSpan".equals(aClass.getName())) {
              try {
                final MethodHandles methodHandles = new MethodHandles(aClass.getClassLoader());
                return new CachedDelegateCaller(
                    methodHandles.privateFieldGetter(aClass, "delegate"));
              } catch (Throwable ignored) {
              }
            }
            return NULL_SPAN_FUNCTION;
          });

  public static <T> AgentSpan maybeFrom(T span) {
    if (span == null) {
      return null;
    }
    try {
      final Function<Object, AgentSpan> extractorFunc =
          (Function<Object, AgentSpan>) DELEGATE_CLASS_VALUE.get(span.getClass());
      return extractorFunc.apply(span);
    } catch (Throwable ignored) {
    }
    return null;
  }

  private SpanExtractorHelper() {}
}
