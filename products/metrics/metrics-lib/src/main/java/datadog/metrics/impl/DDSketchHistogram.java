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
  private double sum;
  // We use a compensated sum to avoid accumulating rounding errors.
  // See https://en.wikipedia.org/wiki/Kahan_summation_algorithm.
  private double sumCompensation; // Low order bits of sum
  private double simpleSum; // Used to compute right sum for non-finite inputs

  public DDSketchHistogram(DDSketch sketch) {
    this.sketch = sketch;
    this.sum = 0;
    this.simpleSum = 0;
    this.sumCompensation = 0;
  }

  @Override
  public double getCount() {
    return sketch.getCount();
  }

  @Override
  public double getSum() {
    // Better error bounds to add both terms as the final sum
    final double tmp = sum + sumCompensation;
    if (Double.isNaN(tmp) && Double.isInfinite(simpleSum)) {
      // If the compensated sum is spuriously NaN from accumulating one or more same-signed infinite
      // values, return the correctly-signed infinity stored in simpleSum.
      return simpleSum;
    } else {
      return tmp;
    }
  }

  @Override
  public boolean isEmpty() {
    return sketch.isEmpty();
  }

  @Override
  public void accept(double value) {
    sketch.accept(value);
    updateSum(value);
  }

  @Override
  public void accept(double value, double count) {
    sketch.accept(value, count);
    updateSum(value * count);
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

  private void updateSum(double value) {
    simpleSum += value;
    sumWithCompensation(value);
  }

  private void sumWithCompensation(double value) {
    final double tmp = value - sumCompensation;
    final double velvel = sum + tmp; // Little wolf of rounding error
    sumCompensation = (velvel - sum) - tmp;
    sum = velvel;
  }
}
