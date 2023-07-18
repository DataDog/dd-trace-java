package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import datadog.trace.bootstrap.instrumentation.api.AgentHistogram;
import java.nio.ByteBuffer;

/** Wrapper around the DDSketch library so that it can be used in an instrumentation */
public class Histogram implements AgentHistogram {
  private final DDSketch sketch;

  public Histogram(DDSketch sketch) {
    this.sketch = sketch;
  }

  @Override
  public double getCount() {
    return sketch.getCount();
  }

  @Override
  public boolean isEmpty() {
    return sketch.isEmpty();
  }

  @Override
  public void accept(double value) {
    sketch.accept(value);
  }

  @Override
  public void accept(double value, double count) {
    sketch.accept(value, count);
  }

  @Override
  public double getValueAtQuantile(double quantile) {
    return sketch.getValueAtQuantile(quantile);
  }

  @Override
  public double getMinValue() {
    return sketch.getMinValue();
  }

  @Override
  public double getMaxValue() {
    return sketch.getMaxValue();
  }

  @Override
  public void clear() {
    sketch.clear();
  }

  @Override
  public ByteBuffer serialize() {
    return sketch.serialize();
  }
}
