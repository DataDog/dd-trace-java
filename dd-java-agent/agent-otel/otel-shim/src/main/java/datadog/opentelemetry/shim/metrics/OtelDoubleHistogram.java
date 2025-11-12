package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelDoubleHistogram extends Instrument implements DoubleHistogram {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleHistogram.class);

  final OtelMeter otelMeter;

  OtelDoubleHistogram(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelDoubleHistogram");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void record(double value) {
    LOGGER.info("(not) recording {} to the OtelDoubleHistogram", value);
  }

  @Override
  public void record(double value, Attributes attributes) {}

  @Override
  public void record(double value, Attributes attributes, Context context) {}

  static class OtelDoubleHistogramBuilder implements DoubleHistogramBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleHistogramBuilder.class);
    InstrumentBuilder builder;

    public OtelDoubleHistogramBuilder(OtelMeter otelMeter, String instrumentName) {
      this.builder =
          new InstrumentBuilder(
              otelMeter, instrumentName, InstrumentType.HISTOGRAM, InstrumentValueType.DOUBLE);
    }

    @Override
    public OtelDoubleHistogramBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public OtelDoubleHistogramBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public DoubleHistogramBuilder setExplicitBucketBoundariesAdvice(List<Double> bucketBoundaries) {
      return DoubleHistogramBuilder.super.setExplicitBucketBoundariesAdvice(bucketBoundaries);
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return builder.swapBuilder(OtelLongHistogram.OtelLongHistogramBuilder::new);
    }

    @Override
    public DoubleHistogram build() {
      return builder.buildSynchronousInstrument(OtelDoubleHistogram::new);
    }
  }
}
