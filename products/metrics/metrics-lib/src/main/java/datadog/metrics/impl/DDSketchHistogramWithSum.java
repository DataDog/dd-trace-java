package datadog.metrics.impl;

import com.datadoghq.sketch.ddsketch.DDSketch;
import datadog.metrics.api.HistogramWithSum;

/** Adds exact summary statistics to the DDSketch wrapper */
public final class DDSketchHistogramWithSum extends DDSketchHistogram implements HistogramWithSum {
  private double sum;
  // We use a compensated sum to avoid accumulating rounding errors.
  // See https://en.wikipedia.org/wiki/Kahan_summation_algorithm.
  private double sumCompensation; // Low order bits of sum
  private double simpleSum; // Used to compute right sum for non-finite inputs

  public DDSketchHistogramWithSum(DDSketch sketch) {
    super(sketch);
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
  public void accept(double value) {
    super.accept(value);
    updateSum(value);
  }

  @Override
  public void accept(double value, double count) {
    super.accept(value, count);
    updateSum(value * count);
  }

  @Override
  public void clear() {
    super.clear();
    sum = 0;
    simpleSum = 0;
    sumCompensation = 0;
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
