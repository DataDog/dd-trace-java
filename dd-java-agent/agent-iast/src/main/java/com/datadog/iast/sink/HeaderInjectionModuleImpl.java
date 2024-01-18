package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.allRangesFromHeader;
import static com.datadog.iast.util.HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN;
import static com.datadog.iast.util.HttpHeader.CONNECTION;
import static com.datadog.iast.util.HttpHeader.LOCATION;
import static com.datadog.iast.util.HttpHeader.ORIGIN;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_ACCEPT;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_LOCATION;
import static com.datadog.iast.util.HttpHeader.SET_COOKIE;
import static com.datadog.iast.util.HttpHeader.UPGRADE;

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
import datadog.trace.api.iast.sink.HeaderInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class HeaderInjectionModuleImpl extends SinkModuleBase implements HeaderInjectionModule {

  private static final Set<HttpHeader> headerInjectionExclusions =
      EnumSet.of(SEC_WEBSOCKET_LOCATION, SEC_WEBSOCKET_ACCEPT, UPGRADE, CONNECTION, LOCATION);

  public HeaderInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onHeader(@Nonnull final String name, @Nullable final String value) {
    if (null == value) {
      return;
    }
    HttpHeader header = HttpHeader.from(name);
    if (headerInjectionExclusions.contains(header)) {
      return;
    }

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

    // TODO (spec) we should fix this as it's not a 100% accurate
    if (header == ACCESS_CONTROL_ALLOW_ORIGIN && allRangesFromHeader(ORIGIN, ranges)) {
      return;
    }

    // TODO (spec): we should fix this, the source header should be 'Cookie' not 'Set-Cookie'
    if ((header == SET_COOKIE) && allRangesFromHeader(SET_COOKIE, ranges)) {
      return;
    }

    // TODO (spec): we are missing the case where the header is reflected from the request

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
