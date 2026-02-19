package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelDoubleHistogram.Builder.validateBoundaries;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.opentelemetry.shim.metrics.data.OtelMetricStorage.newHistogramStorage;
import static java.util.stream.Collectors.toList;

import datadog.opentelemetry.shim.metrics.data.OtelMetricStorage;
import datadog.trace.relocate.api.RatelimitedLogger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelLongHistogram extends OtelInstrument implements LongHistogram {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelLongHistogram.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  OtelLongHistogram(OtelMetricStorage storage) {
    super(storage);
  }

  @Override
  public void record(long value) {
    record(value, Attributes.empty());
  }

  @Override
  public void record(long value, Attributes attributes) {
    if (value < 0) {
      RATELIMITED_LOGGER.warn(
          "Histograms can only record non-negative values. Instrument {} has recorded a negative value.",
          storage.getInstrumentName());
    } else {
      storage.recordLong(value, attributes);
    }
  }

  @Override
  public void record(long value, Attributes attributes, Context unused) {
    record(value, attributes);
  }

  static final class Builder implements LongHistogramBuilder {
    private final OtelMeter meter;
    private final OtelInstrumentBuilder builder;
    private List<Double> bucketBoundaries;

    Builder(OtelMeter meter, OtelInstrumentBuilder builder, List<Double> bucketBoundaries) {
      this.meter = meter;
      this.builder = ofLongs(builder, HISTOGRAM);
      this.bucketBoundaries = bucketBoundaries;
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
    @SuppressFBWarnings("DCN") // match OTel in catching and logging NPE
    public LongHistogramBuilder setExplicitBucketBoundariesAdvice(List<Long> bucketBoundaries) {
      try {
        Objects.requireNonNull(bucketBoundaries, "bucketBoundaries must not be null");
        this.bucketBoundaries =
            validateBoundaries(bucketBoundaries.stream().map(Long::doubleValue).collect(toList()));
      } catch (IllegalArgumentException | NullPointerException e) {
        LOGGER.warn("Error setting explicit bucket boundaries advice: {}", e.getMessage());
      }
      return this;
    }

    @Override
    public LongHistogram build() {
      return new OtelLongHistogram(
          meter.registerStorage(
              builder, descriptor -> newHistogramStorage(descriptor, bucketBoundaries)));
    }
  }
}
