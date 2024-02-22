package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ReflectionInjectionModuleImpl extends SinkModuleBase
    implements ReflectionInjectionModule {

  public ReflectionInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onClassName(@Nullable String value) {
    if (!canBeTainted(value)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(ctx, VulnerabilityType.REFLECTION_INJECTION, value);
  }

  @Override
  public void onMethodName(
      @Nonnull Class<?> clazz, @Nonnull String methodName, @Nullable Class<?>... parameterTypes) {
    checkReflectionInjection(
        ReflectionInjectionModuleImpl::methodEvidence, clazz, methodName, parameterTypes);
  }

  @Override
  public void onFieldName(@Nonnull Class<?> clazz, @Nonnull String fieldName) {
    checkReflectionInjection(ReflectionInjectionModuleImpl::fieldEvidence, clazz, fieldName);
  }

  private void checkReflectionInjection(
      StringEvidenceBuilder stringEvidenceBuilder,
      @Nonnull Class<?> clazz,
      @Nonnull String name,
      @Nullable Class<?>... parameterTypes) {
    if (!canBeTainted(name)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    final TaintedObject taintedObject = to.get(name);
    if (taintedObject == null) {
      return;
    }
    final Range[] ranges =
        Ranges.getNotMarkedRanges(
            taintedObject.getRanges(), VulnerabilityType.REFLECTION_INJECTION.mark());
    if (ranges == null || ranges.length == 0) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final String className = clazz.getName();
    final String evidenceString = stringEvidenceBuilder.build(clazz, name, parameterTypes);
    final Range[] shiftedRanges = new Range[ranges.length];
    for (int i = 0; i < ranges.length; i++) {
      shiftedRanges[i] = ranges[i].shift(className.length() + 1);
    }
    final Evidence result = new Evidence(evidenceString, shiftedRanges);
    report(span, VulnerabilityType.REFLECTION_INJECTION, result);
  }

  private static String fieldEvidence(
      @Nonnull Class<?> clazz, @Nonnull String name, @Nullable Class<?>... parameterTypes) {
    return clazz.getName() + "#" + name;
  }

  private static String methodEvidence(
      @Nonnull Class<?> clazz, @Nonnull String name, @Nullable Class<?>... parameterTypes) {
    return clazz.getName() + "#" + name + "(" + getParameterTypesString(parameterTypes) + ")";
  }

  @Nonnull
  private static String getParameterTypesString(@Nullable Class<?>... parameterTypes) {
    if (parameterTypes == null || parameterTypes.length == 0) {
      return "";
    }
    return Arrays.stream(parameterTypes)
        .map(clazz -> clazz == null ? "UNKNOWN" : clazz.getName())
        .collect(Collectors.joining(", "));
  }

  private interface StringEvidenceBuilder {
    String build(
        @Nonnull Class<?> clazz, @Nonnull String name, @Nullable Class<?>... parameterTypes);
  }
}
