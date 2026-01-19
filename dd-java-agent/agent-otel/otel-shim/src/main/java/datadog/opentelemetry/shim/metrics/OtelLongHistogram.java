package datadog.opentelemetry.shim.metrics;

import static datadog.opentelemetry.shim.metrics.OtelInstrumentBuilder.ofLongs;
import static datadog.opentelemetry.shim.metrics.OtelInstrumentType.HISTOGRAM;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.context.Context;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLongHistogram extends OtelInstrument implements LongHistogram {

  OtelLongHistogram(OtelInstrumentDescriptor descriptor) {
    super(descriptor);
  }

  @Override
  public void record(long value) {
    // FIXME: implement recording
  }

  @Override
  public void record(long value, Attributes attributes) {
    // FIXME: implement recording
  }

  @Override
  public void record(long value, Attributes attributes, Context context) {
    // FIXME: implement recording
  }

  static final class Builder implements LongHistogramBuilder {
    private final OtelInstrumentBuilder instrumentBuilder;

    Builder(OtelInstrumentBuilder builder) {
      this.instrumentBuilder = ofLongs(builder, HISTOGRAM);
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
      // FIXME: implement boundary advice
      return this;
    }

    @Override
    public LongHistogram build() {
      return new OtelLongHistogram(instrumentBuilder.toDescriptor());
    }
  }
}
