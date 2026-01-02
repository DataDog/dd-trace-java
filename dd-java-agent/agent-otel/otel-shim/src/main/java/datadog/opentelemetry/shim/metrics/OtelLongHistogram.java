package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;

import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import java.util.List;

final class OtelLongHistogram {
  static final class Builder implements LongHistogramBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder instrumentBuilder) {
      this.instrumentBuilder = ofLongs(instrumentBuilder, HISTOGRAM);
    }

    @Override
    public LongHistogramBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public LongHistogramBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> bucketBoundaries) {
      throw new UnsupportedOperationException(
          "setExplicitBucketBoundariesAdvice is not yet supported");
    }

    @Override
    public LongHistogram build() {
      throw new UnsupportedOperationException("build is not yet supported");
    }
  }
}
