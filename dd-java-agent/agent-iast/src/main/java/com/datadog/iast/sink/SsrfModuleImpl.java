package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.util.Iterators;
import com.datadog.iast.util.RangeBuilder;
import datadog.trace.api.iast.sink.SsrfModule;
import javax.annotation.Nullable;

public class SsrfModuleImpl extends SinkModuleBase implements SsrfModule {

  public SsrfModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onURLConnection(@Nullable final Object url) {
    if (url == null) {
      return;
    }
    checkInjection(VulnerabilityType.SSRF, url);
  }

  /**
   * if the host or the uri are tainted, we report the url as tainted as well a new range is created
   * covering all the value string in order to simplify the algorithm
   */
  @Override
  public void onURLConnection(@Nullable String value, @Nullable Object host, @Nullable Object uri) {
    if (value == null) {
      return;
    }
    checkInjection(VulnerabilityType.SSRF, Iterators.of(host, uri), new SsrfEvidenceBuilder(value));
  }

  private static class SsrfEvidenceBuilder implements EvidenceBuilder {

    private final String url;

    private SsrfEvidenceBuilder(final String url) {
      this.url = url;
    }

    @Override
    public void tainted(
        final StringBuilder evidence,
        final RangeBuilder ranges,
        final Object value,
        final Range[] valueRanges) {
      if (!ranges.isEmpty()) {
        return; // skip if already tainted
      }
      evidence.append(url);
      final Source source = Ranges.highestPriorityRange(valueRanges).getSource();
      ranges.add(Ranges.forCharSequence(url, source));
    }
  }
}
