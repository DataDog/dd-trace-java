package datadog.metrics.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.mapping.LogarithmicMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;
import datadog.metrics.api.Histogram;

public final class Histograms {

  private static final BitwiseLinearlyInterpolatedMapping INDEX_MAPPING =
      new BitwiseLinearlyInterpolatedMapping(1.0 / 128.0);
  // use the same gamma and index offset as the Datadog backend, to avoid doing any conversions in
  // the backend
  // that would lead to a loss of precision
  private static final LogarithmicMapping LOG_INDEX_MAPPING =
      new LogarithmicMapping(1.015625, 1.8761281912861705);

  public static Histogram newHistogram() {
    DDSketch sketch = new DDSketch(INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
    return new DDSketchHistogram(sketch);
  }

  public static Histogram newLogHistogram() {
    DDSketch sketch = new DDSketch(LOG_INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
    return new DDSketchHistogram(sketch);
  }

  public static Histogram newHistogram(double relativeAccuracy, int maxNumBins) {
    DDSketch sketch = DDSketches.logarithmicCollapsingLowestDense(relativeAccuracy, maxNumBins);
    return new DDSketchHistogram(sketch);
  }
}
