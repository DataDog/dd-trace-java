package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.allRangesFromAnyHeader;
import static com.datadog.iast.taint.Ranges.allRangesFromHeader;
import static com.datadog.iast.taint.Ranges.allRangesFromSource;
import static com.datadog.iast.taint.Ranges.rangeFromHeader;
import static com.datadog.iast.util.HttpHeader.ACCEPT_ENCODING;
import static com.datadog.iast.util.HttpHeader.CACHE_CONTROL;
import static com.datadog.iast.util.HttpHeader.CONNECTION;
import static com.datadog.iast.util.HttpHeader.COOKIE;
import static com.datadog.iast.util.HttpHeader.LOCATION;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_ACCEPT;
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_LOCATION;
import static com.datadog.iast.util.HttpHeader.UPGRADE;
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_NAME;

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
      if (shouldIgnoreHeader(valueRanges)) {
        return;
      }
      evidence.append(name).append(": ").append(value);
      ranges.add(valueRanges, name.length() + 2);
    }

    private boolean shouldIgnoreHeader(final Range[] valueRanges) {
      if (header != null) {
        switch (header) {
          case SET_COOKIE:
          case SET_COOKIE2:
            if (ignoreSetCookieHeader(valueRanges)) {
              return true;
            }
            break;
          case PRAGMA:
            if (ignorePragmaHeader(valueRanges)) {
              return true;
            }
            break;
          case TRANSFER_ENCODING:
          case CONTENT_ENCODING:
            if (ignoreEncodingHeader(valueRanges)) {
              return true;
            }
            break;
          case VARY:
            if (ignoreVaryHeader(valueRanges)) {
              return true;
            }
            break;
        }
      }

      return ignoreAccessControlAllow(valueRanges) || ignoreReflectedHeader(valueRanges);
    }

    /** Ignore pragma headers when the source is the cache control header. */
    private boolean ignorePragmaHeader(final Range[] ranges) {
      return allRangesFromHeader(CACHE_CONTROL, ranges);
    }

    /**
     * Ignore transfer and content encoding headers when the source is the accept encoding header.
     */
    private boolean ignoreEncodingHeader(final Range[] ranges) {
      return allRangesFromHeader(ACCEPT_ENCODING, ranges);
    }

    /** Ignore vary header when the sources are only header names */
    private boolean ignoreVaryHeader(final Range[] ranges) {
      return allRangesFromSource(REQUEST_HEADER_NAME, ranges);
    }

    /**
     * Exclude access-control-allow-*: when the header starts with access-control-allow- and the
     * source of the tainted range is a request header
     */
    private boolean ignoreAccessControlAllow(final Range[] ranges) {
      return nameMatchesPrefix(ACCESS_CONTROL_ALLOW_PREFIX) && allRangesFromAnyHeader(ranges);
    }

    /** Exclude set-cookie header if the source of all the tainted ranges are cookies */
    private boolean ignoreSetCookieHeader(final Range[] ranges) {
      return allRangesFromHeader(COOKIE, ranges);
    }

    /** Exclude when the header is reflected from the request */
    private boolean ignoreReflectedHeader(final Range[] ranges) {
      return ranges.length == 1 && rangeFromHeader(name, ranges[0]);
    }

    private boolean nameMatchesPrefix(final String prefix) {
      return name.regionMatches(true, 0, prefix, 0, prefix.length());
    }
  }
}
