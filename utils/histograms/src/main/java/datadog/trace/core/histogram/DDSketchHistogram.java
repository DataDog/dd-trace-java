package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;

public class DDSketchHistogram implements Histogram, HistogramFactory {

  private final DDSketch sketch;

  public DDSketchHistogram() {
    this.sketch = DDSketch.balancedCollapsingLowest(0.01, 1024);
  }

  @Override
  public void accept(long value) {
    sketch.accept(value);
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
