package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.List;

final class OtelDoubleHistogram implements DoubleHistogram {

  @Override
  public void record(double value) {
    // FIXME: implement recording
  }

  @Override
  public void record(double value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void record(double value, Attributes attributes, Context context) {
    // FIXME: implement recording
  }

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
      // FIXME: implement boundary advice
      return this;
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return new OtelLongHistogram.Builder(instrumentBuilder);
    }

    @Override
    public DoubleHistogram build() {
      return new OtelDoubleHistogram();
    }
  }
}
