package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Range;
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
      if (value != null && url.equals(value.toString())) {
        // safe path, the URL is exactly the same
        ranges.add(valueRanges);
      } else {
        final Range range = Ranges.highestPriorityRange(valueRanges);
        final String tainted = substring(value, range);
        final int offset = tainted == null ? -1 : url.indexOf(tainted);
        if (offset >= 0) {
          // use the specific part of the tainted URL
          ranges.add(range.shift(offset));
        } else {
          // resort to fully taint the URL
          ranges.add(Ranges.forCharSequence(url, range.getSource()));
        }
      }
    }

    @Nullable
    private String substring(Object value, final Range range) {
      if (value == null) {
        return null;
      }
      final String stringValue = value.toString();
      if (range.getStart() + range.getLength() > stringValue.length()) {
        return null;
      }
      return stringValue.substring(range.getStart(), range.getStart() + range.getLength());
    }
  }
}
