package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.sink.HeaderInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class HeaderInjectionModuleImpl extends SinkModuleBase implements HeaderInjectionModule {

  private static final Set<String> headerInjectionExclusions =
      new HashSet<String>(
          Arrays.asList(
              "Sec-WebSocket-Location".toUpperCase(),
              "Sec-WebSocket-Accept".toUpperCase(),
              "Upgrade".toUpperCase(),
              "Connection".toUpperCase(),
              "location".toUpperCase()));

  public HeaderInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onHeader(@Nonnull final String name, @Nullable final String value) {
    if (null == value) {
      return;
    }
    boolean headerInjectionExclusion = headerInjectionExclusions.contains(name.toUpperCase());

    if (!headerInjectionExclusion) {
      final AgentSpan span = AgentTracer.activeSpan();
      final IastRequestContext ctx = IastRequestContext.get(span);
      if (ctx == null) {
        return;
      }

      final TaintedObject taintedObject = ctx.getTaintedObjects().get(value);
      if (null == taintedObject) {
        return;
      }

      final Range[] ranges =
          Ranges.getNotMarkedRanges(
              taintedObject.getRanges(), VulnerabilityType.HEADER_INJECTION.mark());
      if (ranges == null || ranges.length == 0) {
        return;
      }

      if ("Access-Control-Allow-Origin".equalsIgnoreCase(name)) {
        boolean allRangesFromOrigin = true;
        for (Range range : ranges) {
          if (null != range.getSource().getName()
              && range.getSource().getOrigin() == SourceTypes.REQUEST_HEADER_VALUE
              && !range.getSource().getName().equalsIgnoreCase("origin")) {
            allRangesFromOrigin = false;
          }
        }
        if (allRangesFromOrigin) {
          return;
        }
      }

      if ("Set-Cookie".equalsIgnoreCase(name)) {
        boolean allRangesFromCookie = true;
        for (Range range : ranges) {
          if (null != range.getSource().getName()
              && range.getSource().getOrigin() == SourceTypes.REQUEST_HEADER_VALUE
              && !range.getSource().getName().equalsIgnoreCase("Set-Cookie")) {
            allRangesFromCookie = false;
          }
        }
        if (allRangesFromCookie) {
          return;
        }
      }

      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      final String evidenceString = name + ": " + value;
      final Range[] shiftedRanges = new Range[ranges.length];
      for (int i = 0; i < ranges.length; i++) {
        shiftedRanges[i] = ranges[i].shift(name.length() + 2);
      }
      final Evidence result = new Evidence(evidenceString, shiftedRanges);
      report(span, VulnerabilityType.HEADER_INJECTION, result);
    }
  }
}
