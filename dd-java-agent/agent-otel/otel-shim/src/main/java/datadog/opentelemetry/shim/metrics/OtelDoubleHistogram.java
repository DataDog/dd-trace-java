package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import java.util.List;

final class OtelDoubleHistogram {
  static final class Builder implements DoubleHistogramBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofDoubles(meter, instrumentName, HISTOGRAM);
    }

    @Override
    public DoubleHistogramBuilder setDescription(String description) {
      instrumentBuilder.setDescription(description);
      return this;
    }

    @Override
    public DoubleHistogramBuilder setUnit(String unit) {
      instrumentBuilder.setUnit(unit);
      return this;
    }

    @Override
    public DoubleHistogramBuilder setExplicitBucketBoundariesAdvice(List<Double> bucketBoundaries) {
      throw new UnsupportedOperationException(
          "setExplicitBucketBoundariesAdvice is not yet supported");
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return new OtelLongHistogram.Builder(instrumentBuilder);
    }

    @Override
    public DoubleHistogram build() {
      throw new UnsupportedOperationException("build is not yet supported");
    }
  }
}
