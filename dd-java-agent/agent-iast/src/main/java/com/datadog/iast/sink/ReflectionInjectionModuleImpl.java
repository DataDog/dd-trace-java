package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ReflectionInjectionModuleImpl extends SinkModuleBase
    implements ReflectionInjectionModule {

  public ReflectionInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onClassName(@Nullable String className) {
    if (!canBeTainted(className)) {
      return;
    }
    checkInjection(VulnerabilityType.REFLECTION_INJECTION, className);
  }

  @Override
  public void onMethodName(
      @Nonnull Class<?> clazz, @Nonnull String methodName, @Nullable Class<?>... parameterTypes) {
    if (!canBeTainted(methodName)) {
      return;
    }
    checkInjection(
        VulnerabilityType.REFLECTION_INJECTION,
        methodName,
        new MethodEvidenceBuilder(clazz, parameterTypes));
  }

  @Override
  public void onFieldName(@Nonnull Class<?> clazz, @Nonnull String fieldName) {
    if (!canBeTainted(fieldName)) {
      return;
    }
    checkInjection(
        VulnerabilityType.REFLECTION_INJECTION, fieldName, new FieldEvidenceBuilder(clazz));
  }

  private static class MethodEvidenceBuilder implements EvidenceBuilder {

    private final Class<?> clazz;
    @Nullable private final Class<?>[] parameterTypes;

    private MethodEvidenceBuilder(final Class<?> clazz, @Nullable final Class<?>[] parameterTypes) {
      this.clazz = clazz;
      this.parameterTypes = parameterTypes;
    }

    @Override
    public void tainted(
        final StringBuilder evidence,
        final RangeBuilder ranges,
        final Object value,
        final Range[] valueRanges) {
      final String className = clazz.getName();
      evidence.append(className).append('#').append(value).append('(');
      if (parameterTypes != null) {
        for (int i = 0; i < parameterTypes.length; i++) {
          if (i > 0) {
            evidence.append(", ");
          }
          final Class<?> parameter = parameterTypes[i];
          evidence.append(parameter == null ? "UNKNOWN" : parameter.getName());
        }
      }
      evidence.append(')');
      ranges.add(valueRanges, className.length() + 1);
    }
  }

  private static class FieldEvidenceBuilder implements EvidenceBuilder {

    private final Class<?> clazz;

    private FieldEvidenceBuilder(final Class<?> clazz) {
      this.clazz = clazz;
    }

    @Override
    public void tainted(
        final StringBuilder evidence,
        final RangeBuilder ranges,
        final Object value,
        final Range[] valueRanges) {
      final String className = clazz.getName();
      evidence.append(className).append('#').append(value);
      ranges.add(valueRanges, className.length() + 1);
    }
  }
}
