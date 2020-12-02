package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.CubicallyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.PaginatedStore;

public class DDSketchHistogram implements Histogram, HistogramFactory {

  private final DDSketch sketch;

  public DDSketchHistogram() {
    this.sketch = new DDSketch(new CubicallyInterpolatedMapping(0.01), PaginatedStore::new);
  }

  @Override
  public void accept(long value) {
    sketch.accept(value);
  }

  @Override
  public void clear() {
    this.sketch.clear();
  }

  @Override
  public byte[] serialize() {
    return sketch.toProto().toByteArray();
  }

  @Override
  public Histogram newHistogram() {
    return new DDSketchHistogram();
  }
}
