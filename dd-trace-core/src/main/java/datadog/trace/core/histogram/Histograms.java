package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;

public final class Histograms {

  private static final BitwiseLinearlyInterpolatedMapping INDEX_MAPPING =
      new BitwiseLinearlyInterpolatedMapping(1.0 / 128.0);

  public static Histogram newHistogram() {
    DDSketch sketch = new DDSketch(INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
    return new Histogram(sketch);
  }

  public static Histogram newHistogram(double relativeAccuracy, int maxNumBins) {
    DDSketch sketch = DDSketches.logarithmicCollapsingLowestDense(relativeAccuracy, maxNumBins);
    return new Histogram(sketch);
  }
}
