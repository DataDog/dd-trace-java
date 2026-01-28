package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofDoubles;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import datadog.trace.relocate.api.RatelimitedLogger;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
final class OtelDoubleHistogram extends OtelInstrument implements DoubleHistogram {
  private static final RatelimitedLogger log =
      new RatelimitedLogger(
          LoggerFactory.getLogger(OtelDoubleHistogram.class), 5, TimeUnit.MINUTES);

  @Nullable private final List<Double> bucketBoundaries;

  OtelDoubleHistogram(OtelInstrumentDescriptor descriptor, List<Double> bucketBoundaries) {
    super(descriptor);
    this.bucketBoundaries = bucketBoundaries;
  }

  @Override
  public void record(double value) {
    record(value, Attributes.empty());
  }

  @Override
  public void record(double value, Attributes attributes) {
    if (value < 0) {
      log.warn(
          "Histograms can only record non-negative values. Instrument "
              + getDescriptor().getName()
              + " has recorded a negative value.");
    } else {
      // FIXME: implement recording
    }
  }

  @Override
  public void record(double value, Attributes attributes, Context unused) {
    record(value, attributes);
  }

  static final class Builder implements DoubleHistogramBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    private List<Double> bucketBoundaries;

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
      try {
        Objects.requireNonNull(bucketBoundaries, "bucketBoundaries must not be null");
        this.bucketBoundaries =
            validateBoundaries(unmodifiableList(new ArrayList<>(bucketBoundaries)));
      } catch (NullPointerException | IllegalArgumentException e) {
        log.warn("Error setting explicit bucket boundaries advice: " + e.getMessage());
      }
      return this;
    }

    @Override
    public LongHistogramBuilder ofLongs() {
      return new OtelLongHistogram.Builder(instrumentBuilder, bucketBoundaries);
    }

    @Override
    public DoubleHistogram build() {
      return new OtelDoubleHistogram(instrumentBuilder.toDescriptor(), bucketBoundaries);
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
