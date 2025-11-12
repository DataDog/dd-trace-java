package datadog.opentelemetry.shim.metrics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtelLongHistogram extends Instrument implements LongHistogram {

  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongHistogram.class);

  final OtelMeter otelMeter;

  OtelLongHistogram(OtelMeter otelMeter, MetricStreamIdentity metricStreamIdentity) {
    super(metricStreamIdentity);
    LOGGER.info("creating a new OtelLongHistogram");
    this.otelMeter = otelMeter;
    /// this.storage = storage;
  }

  @Override
  public void record(long value) {
    LOGGER.info("(not) recording {} to the OtelLongHistogram", value);
  }

  @Override
  public void record(long value, Attributes attributes) {}

  @Override
  public void record(long value, Attributes attributes, Context context) {}

  static class OtelLongHistogramBuilder implements LongHistogramBuilder {
    private static final Logger LOGGER =
        LoggerFactory.getLogger(OtelLongHistogram.OtelLongHistogramBuilder.class);
    InstrumentBuilder builder;

    public OtelLongHistogramBuilder(
        OtelMeter otelMeter, String instrumentName, String description, String unit) {
      this.builder =
          new InstrumentBuilder(
                  otelMeter, instrumentName, InstrumentType.HISTOGRAM, InstrumentValueType.LONG)
              .setUnit(unit)
              .setDescription(description);
    }

    @Override
    public LongHistogramBuilder setDescription(String description) {
      builder.setDescription(description);
      return this;
    }

    @Override
    public LongHistogramBuilder setUnit(String unit) {
      builder.setUnit(unit);
      return this;
    }

    @Override
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> bucketBoundaries) {
      return LongHistogramBuilder.super.setExplicitBucketBoundariesAdvice(bucketBoundaries);
    }

    @Override
    public LongHistogram build() {
      return builder.buildSynchronousInstrument(OtelLongHistogram::new);
    }
  }
}
