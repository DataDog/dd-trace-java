package datadog.trace.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping;
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore;

public final class Histograms {

  private static final BitwiseLinearlyInterpolatedMapping INDEX_MAPPING =
      new BitwiseLinearlyInterpolatedMapping(1.0 / 128.0);

  public static DDSketch newHistogram() {
    return new DDSketch(INDEX_MAPPING, () -> new CollapsingLowestDenseStore(1024));
  }
}
