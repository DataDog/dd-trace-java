package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;
import java.nio.ByteBuffer;

public final class DDSketchHistogram implements Histogram {

  private final DDSketch sketch;

  public DDSketchHistogram() {
    this(
        new DDSketch(
            new BitwiseLinearlyInterpolatedMapping(1.0 / 128.0),
            () -> new CollapsingLowestDenseStore(1024)));
  }

  public DDSketchHistogram(DDSketch sketch) {
    this.sketch = sketch;
  }

  @Override
  public void accept(long value) {
    sketch.accept(value);
  }

  @Override
  public void accept(double value) {
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
}
