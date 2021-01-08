package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;
import java.nio.ByteBuffer;

public final class DDSketchHistogram implements Histogram, HistogramFactory {

  private final DDSketch sketch;

  public DDSketchHistogram() {
    this.sketch =
        new DDSketch(
            new BitwiseLinearlyInterpolatedMapping(0.01),
            () -> new CollapsingLowestDenseStore(1024));
  }

  @Override
  public void accept(long value) {
    sketch.accept(value);
  }

  @Override
  public double valueAtQuantile(double quantile) {
    if (sketch.isEmpty()) {
      return 0D;
    }
    return sketch.getValueAtQuantile(quantile);
  }

  @Override
  public double max() {
    if (sketch.isEmpty()) {
      return 0D;
    }
    return sketch.getMaxValue();
  }

  @Override
  public void clear() {
    this.sketch.clear();
  }

  @Override
  public ByteBuffer serialize() {
    return sketch.serialize();
  }

  @Override
  public Histogram newHistogram() {
    return new DDSketchHistogram();
  }
}
