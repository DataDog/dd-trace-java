package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.allRangesFromAnyHeader;
import static com.datadog.iast.taint.Ranges.allRangesFromHeader;
import static com.datadog.iast.taint.Ranges.rangeFromHeader;
import static com.datadog.iast.util.HttpHeader.CONNECTION;
import static com.datadog.iast.util.HttpHeader.COOKIE;
import static com.datadog.iast.util.HttpHeader.LOCATION;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_ACCEPT;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_LOCATION;
import static com.datadog.iast.util.HttpHeader.SET_COOKIE;
import static com.datadog.iast.util.HttpHeader.UPGRADE;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.HttpHeader;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.sink.HeaderInjectionModule;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class HeaderInjectionModuleImpl extends SinkModuleBase implements HeaderInjectionModule {

  private static final Set<HttpHeader> headerInjectionExclusions =
      EnumSet.of(SEC_WEBSOCKET_LOCATION, SEC_WEBSOCKET_ACCEPT, UPGRADE, CONNECTION, LOCATION);

  private static final String ACCESS_CONTROL_ALLOW_PREFIX = "ACCESS-CONTROL-ALLOW-";

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

    checkInjection(
        VulnerabilityType.HEADER_INJECTION,
        value,
        new HeaderInjectionEvidenceBuilder(name, header));
  }

  private static class HeaderInjectionEvidenceBuilder implements EvidenceBuilder {

    private final String name;
    @Nullable private final HttpHeader header;

    private HeaderInjectionEvidenceBuilder(final String name, @Nullable final HttpHeader header) {
      this.name = name;
      this.header = header;
    }

    @Override
    public void tainted(
        final StringBuilder evidence,
        final RangeBuilder ranges,
        final Object value,
        final Range[] valueRanges) {
      // Exclude access-control-allow-*: when the header starts with access-control-allow- and
      // the source of the tainted range is a request header
      if (name.regionMatches(
              true, 0, ACCESS_CONTROL_ALLOW_PREFIX, 0, ACCESS_CONTROL_ALLOW_PREFIX.length())
          && allRangesFromAnyHeader(valueRanges)) {
        return;
      }

      // Exclude set-cookie header if the source of all the tainted ranges are cookies
      if ((header == SET_COOKIE) && allRangesFromHeader(COOKIE, valueRanges)) {
        return;
      }

      // Exclude when the header is reflected from the request
      if (valueRanges.length == 1 && rangeFromHeader(name, valueRanges[0])) {
        return;
      }

      evidence.append(name).append(": ").append(value);
      ranges.add(valueRanges, name.length() + 2);
    }
  }
}
