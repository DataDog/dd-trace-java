package datadog.metrics.impl;

import static java.util.Collections.binarySearch;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.DDSketches;
import com.datadoghq.sketch.ddsketch.Serializer;
import com.datadoghq.sketch.ddsketch.encoding.Output;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.mapping.IndexMapping;
import com.datadoghq.sketch.ddsketch.mapping.LogarithmicMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;
import datadog.metrics.api.Histogram;
import datadog.metrics.api.Histograms;
import java.util.List;

public final class DDSketchHistograms implements Histograms.Factory {
  private static final BitwiseLinearlyInterpolatedMapping INDEX_MAPPING =
      new BitwiseLinearlyInterpolatedMapping(1.0 / 128.0);
  // use the same gamma and index offset as the Datadog backend, to avoid doing
  // any conversions in the backend that would lead to a loss of precision
  private static final LogarithmicMapping LOG_INDEX_MAPPING =
      new LogarithmicMapping(1.015625, 1.8761281912861705);
  public static final Histograms.Factory FACTORY = new DDSketchHistograms();

  private DDSketchHistograms() {}

  @Override
  public Histogram newHistogram() {
    DDSketch sketch = new DDSketch(INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
    return new DDSketchHistogram(sketch);
  }

  @Override
  public Histogram newLogHistogram() {
    DDSketch sketch = new DDSketch(LOG_INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
    return new DDSketchHistogram(sketch);
  }

  @Override
  public Histogram newHistogram(double relativeAccuracy, int maxNumBins) {
    DDSketch sketch = DDSketches.logarithmicCollapsingLowestDense(relativeAccuracy, maxNumBins);
    return new DDSketchHistogram(sketch);
  }

  @Override
  public Histogram newHistogram(List<Double> binBoundaries) {
    validateBoundaries(binBoundaries);
    DDSketch sketch =
        new DDSketch(
            new ExplicitBoundaries(binBoundaries),
            () -> new CollapsingLowestDenseStore(0), // negative store not used
            () -> new CollapsingLowestDenseStore(binBoundaries.size() + 1),
            Double.NEGATIVE_INFINITY); // assign all negative/zero values to first bin
    return new DDSketchHistogram(sketch);
  }

  static void validateBoundaries(List<Double> boundaries) {
    if (boundaries.isEmpty()) {
      return;
    }
    Double previousBoundary = null;
    for (Double boundary : boundaries) {
      if (boundary == null) {
        throw new IllegalArgumentException("invalid bucket boundary: null");
      }
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
    if (boundaries.get(0) < 0) {
      throw new IllegalArgumentException("invalid bucket boundary: " + boundaries.get(0));
    }
    if (boundaries.get(boundaries.size() - 1) == Double.POSITIVE_INFINITY) {
      throw new IllegalArgumentException("invalid bucket boundary: +Infinity");
    }
  }

  static final class ExplicitBoundaries implements IndexMapping {
    private final List<Double> binBoundaries;

    ExplicitBoundaries(List<Double> binBoundaries) {
      this.binBoundaries = binBoundaries;
    }

    @Override
    public int index(double value) {
      // negative index means no exact match, use '~' to find the closest index
      int index = binarySearch(binBoundaries, value);
      return index >= 0 ? index : ~index;
    }

    @Override
    public double lowerBound(int index) {
      if (index == 0) {
        return Double.NEGATIVE_INFINITY;
      } else {
        return binBoundaries.get(index - 1);
      }
    }

    @Override
    public double upperBound(int index) {
      if (index < binBoundaries.size()) {
        return binBoundaries.get(index);
      } else {
        return Double.POSITIVE_INFINITY;
      }
    }

    @Override
    public double minIndexableValue() {
      return Double.NEGATIVE_INFINITY;
    }

    @Override
    public double maxIndexableValue() {
      return Double.POSITIVE_INFINITY;
    }

    @Override
    public double value(int index) {
      throw new UnsupportedOperationException();
    }

    @Override
    public double relativeAccuracy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void encode(Output output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int serializedSize() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void serialize(Serializer serializer) {
      throw new UnsupportedOperationException();
    }
  }
}
