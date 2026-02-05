package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;
import static datadog.opentelemetry.shim.metrics.data.OtelMetricStorage.newHistogramStorage;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import datadog.trace.relocate.api.RatelimitedLogger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelDoubleHistogram extends OtelInstrument implements DoubleHistogram {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelDoubleHistogram.class);
  private static final RatelimitedLogger RATELIMITED_LOGGER =
      new RatelimitedLogger(LOGGER, 5, TimeUnit.MINUTES);

  OtelDoubleHistogram(OtelInstrumentBuilder builder, List<Double> bucketBoundaries) {
    super(builder.build(descriptor -> newHistogramStorage(descriptor, bucketBoundaries)));
  }

  @Override
  public void record(double value) {
    record(value, Attributes.empty());
  }

  @Override
  public void record(double value, Attributes attributes) {
    if (value < 0) {
      RATELIMITED_LOGGER.warn(
          "Histograms can only record non-negative values. Instrument {} has recorded a negative value.",
          storage.getDescriptor().getName());
    } else {
      storage.recordDouble(value, attributes);
    }
  }

  @Override
  public void record(double value, Attributes attributes, Context unused) {
    record(value, attributes);
  }

  static final class Builder implements DoubleHistogramBuilder {
    private static final List<Double> DEFAULT_BOUNDARIES =
        asList(
            0d, 5d, 10d, 25d, 50d, 75d, 100d, 250d, 500d, 750d, 1_000d, 2_500d, 5_000d, 7_500d,
            10_000d);

    private final OtelInstrumentBuilder instrumentBuilder;
    private List<Double> bucketBoundaries;

    Builder(OtelMeter meter, String instrumentName) {
      this.instrumentBuilder = ofDoubles(meter, instrumentName, HISTOGRAM);
      this.bucketBoundaries = DEFAULT_BOUNDARIES;
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
    @SuppressFBWarnings("DCN") // match OTel in catching and logging NPE
    public DoubleHistogramBuilder setExplicitBucketBoundariesAdvice(List<Double> bucketBoundaries) {
      try {
        Objects.requireNonNull(bucketBoundaries, "bucketBoundaries must not be null");
        this.bucketBoundaries =
            validateBoundaries(unmodifiableList(new ArrayList<>(bucketBoundaries)));
      } catch (IllegalArgumentException | NullPointerException e) {
        LOGGER.warn("Error setting explicit bucket boundaries advice: {}", e.getMessage());
      }
      return this;
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return new OtelLongHistogram.Builder(instrumentBuilder, bucketBoundaries);
    }

    @Override
    public DoubleHistogram build() {
      return new OtelDoubleHistogram(instrumentBuilder, bucketBoundaries);
    }

    static List<Double> validateBoundaries(List<Double> boundaries) {
      if (boundaries.isEmpty()) {
        return emptyList();
      }
      if (boundaries.get(0) == Double.NEGATIVE_INFINITY) {
        throw new IllegalArgumentException("invalid bucket boundary: -Inf");
      }
      if (boundaries.get(boundaries.size() - 1) == Double.POSITIVE_INFINITY) {
        throw new IllegalArgumentException("invalid bucket boundary: +Inf");
      }
      Double previousBoundary = null;
      for (Double boundary : boundaries) {
        if (boundary.isNaN()) {
          throw new IllegalArgumentException("invalid bucket boundary: NaN");
        }
        if (previousBoundary != null && previousBoundary >= boundary) {
          throw new IllegalArgumentException(
              "Bucket boundaries must be in increasing order: "
                  + previousBoundary
                  + " >= "
                  + boundary);
        }
        previousBoundary = boundary;
      }
      return boundaries;
    }
  }
}
