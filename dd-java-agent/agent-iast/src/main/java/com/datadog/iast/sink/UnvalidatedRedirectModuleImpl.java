package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.allRangesFromHeader;
import static com.datadog.iast.taint.Tainteds.canBeTainted;
import static com.datadog.iast.util.HttpHeader.LOCATION;
import static com.datadog.iast.util.HttpHeader.REFERER;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class UnvalidatedRedirectModuleImpl extends SinkModuleBase
    implements UnvalidatedRedirectModule {

  public UnvalidatedRedirectModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRedirect(final @Nullable String value) {
    if (!canBeTainted(value)) {
      return;
    }
    checkUnvalidatedRedirect(value);
  }

  @Override
  public void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method) {
    if (!canBeTainted(value)) {
      return;
    }
    checkUnvalidatedRedirect(value, clazz, method);
  }

  @Override
  public void onURIRedirect(@Nullable URI uri) {
    if (uri == null) {
      return;
    }
    checkUnvalidatedRedirect(uri);
  }

  @Override
  public void onHeader(@Nonnull final String name, @Nullable final String value) {
    if (value != null && LOCATION.matches(name)) {
      onRedirect(value);
    }
  }

  private void checkUnvalidatedRedirect(@Nonnull final Object value) {
    checkUnvalidatedRedirect(value, null, null);
  }

  private void checkUnvalidatedRedirect(
      @Nonnull final Object value, @Nullable final String clazz, @Nullable final String method) {
    checkInjection(
        VulnerabilityType.UNVALIDATED_REDIRECT,
        value,
        new UnvalidatedRedirectEvidenceBuilder(),
        new UnvalidatedRedirectLocationSupplier(clazz, method));
  }

  private static class UnvalidatedRedirectEvidenceBuilder implements EvidenceBuilder {

    @Override
    public void tainted(
        final StringBuilder evidence,
        final RangeBuilder ranges,
        final Object value,
        final Range[] valueRanges) {
      if (allRangesFromHeader(REFERER, valueRanges)) {
        return;
      }
      evidence.append(value);
      ranges.add(valueRanges);
    }
  }

  private class UnvalidatedRedirectLocationSupplier implements LocationSupplier {
    @Nullable private final String clazz;
    @Nullable private final String method;

    private UnvalidatedRedirectLocationSupplier(
        @Nullable final String clazz, @Nullable final String method) {
      this.clazz = clazz;
      this.method = method;
    }

    @Override
    public Location build(@Nullable final AgentSpan span) {
      if (clazz != null && method != null) {
        return Location.forSpanAndClassAndMethod(span, clazz, method);
      } else {
        return Location.forSpanAndStack(span, getCurrentStackTrace());
      }
    }
  }
}
