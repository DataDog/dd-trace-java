package datadog.metrics.impl;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.IndexMapping;
import com.datadoghq.sketch.ddsketch.store.Bin;
import datadog.metrics.api.Histogram;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Wrapper around the DDSketch library so that it can be used in an instrumentation */
public class DDSketchHistogram implements Histogram {
  private final DDSketch sketch;

  public DDSketchHistogram(DDSketch sketch) {
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
  public List<Double> getBinBoundaries() {
    List<Double> boundaries = new ArrayList<>();
    // Bin boundaries are defined as a sequence of upper bounds. When our sketch contains gaps we
    // must introduce extra bounds to represent the lower bound of any bins after a gap; otherwise
    // it would look like that bin covers not just its original range, but the gap as well.
    int lastBinIndex = -1;
    IndexMapping indexMapping = sketch.getIndexMapping();
    Iterator<Bin> bins = sketch.getPositiveValueStore().getAscendingIterator();
    while (bins.hasNext()) {
      Bin bin = bins.next();
      int binIndex = bin.getIndex();
      if (lastBinIndex < binIndex - 1) {
        // gap detected, introduce boundary representing current bin's lower bound
        boundaries.add(indexMapping.upperBound(binIndex - 1));
      }
      boundaries.add(indexMapping.upperBound(binIndex));
      lastBinIndex = binIndex;
    }
    return boundaries;
  }

  @Override
  public List<Double> getBinCounts() {
    List<Double> counts = new ArrayList<>();
    // to maintain alignment with getBoundaries we must introduce zero counts for
    // boundaries inserted to represent the lower bound of any bins after a gap.
    int lastBinIndex = -1;
    Iterator<Bin> bins = sketch.getPositiveValueStore().getAscendingIterator();
    while (bins.hasNext()) {
      Bin bin = bins.next();
      int binIndex = bin.getIndex();
      if (lastBinIndex < binIndex - 1) {
        // gap detected, insert zero count for boundary introduced by getBoundaries
        counts.add(0d);
      }
      counts.add(bin.getCount());
      lastBinIndex = binIndex;
    }
    return counts;
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
