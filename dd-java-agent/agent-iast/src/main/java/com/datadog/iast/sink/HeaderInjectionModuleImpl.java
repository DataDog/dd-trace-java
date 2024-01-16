package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.HttpHeader;
import datadog.trace.api.iast.IastContext;
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

  private static final Set<HttpHeader> headerInjectionExclusions =
      new HashSet<HttpHeader>(
          Arrays.asList(
              HttpHeader.Values.SEC_WEBSOCKET_LOCATION,
              HttpHeader.Values.SEC_WEBSOCKET_ACCEPT,
              HttpHeader.Values.UPGRADE,
              HttpHeader.Values.CONNECTION,
              HttpHeader.Values.LOCATION));

  public HeaderInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onHeader(@Nonnull final String name, @Nullable final String value) {
    if (null == value) {
      return;
    }
    HttpHeader header = HttpHeader.from(name);
    boolean headerInjectionExclusion = headerInjectionExclusions.contains(header);

    if (!headerInjectionExclusion) {
      final IastContext ctx = IastContext.Provider.get();
      if (ctx == null) {
        return;
      }
      final TaintedObjects to = ctx.getTaintedObjects();
      final TaintedObject taintedObject = to.get(value);
      if (null == taintedObject) {
        return;
      }

      final Range[] ranges =
          Ranges.getNotMarkedRanges(
              taintedObject.getRanges(), VulnerabilityType.HEADER_INJECTION.mark());
      if (ranges == null || ranges.length == 0) {
        return;
      }

      if (1 == ranges.length){
        if (ranges[0].getSource().getOrigin() == SourceTypes.REQUEST_HEADER_VALUE){
          if (name.equalsIgnoreCase(ranges[0].getSource().getName())) {
            return;
          }
        }

      }
      if (header == HttpHeader.Values.ACCESS_CONTROL_ALLOW_ORIGIN) {
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

      if (header == HttpHeader.Values.SET_COOKIE) {
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

      final AgentSpan span = AgentTracer.activeSpan();
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
