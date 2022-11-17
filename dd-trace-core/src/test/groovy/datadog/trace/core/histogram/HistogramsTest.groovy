package datadog.trace.core.histogram

import com.datadoghq.sketch.ddsketch.DDSketchProtoBinding
import com.datadoghq.sketch.ddsketch.proto.DDSketch
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

class HistogramsTest extends DDSpecification {

  @Shared
  SplittableRandom seededRandom = new SplittableRandom(0)

  @Shared
  def poisson = { List<Double> params ->
    return -Math.log(seededRandom.nextDouble()) / params[0]
  }

  @Shared
  def uniform = { List<Double> params ->
    double min = params[0]
    double max = params[1]
    return min + (seededRandom.nextDouble() * (max - min))
  }

  @Shared
  def normal = { List<Double> params ->
    double mean = params[0]
    double stddev = params[1]
    return mean + ThreadLocalRandom.current().nextGaussian() * stddev
  }

  @Shared
  Double[] quantiles = [0.5D, 0.75D, 0.9D, 0.95D, 0.99D]

  def "test quantiles have 1% relative error"() {
    setup:
    def histogram = Histograms.newHistogram()
    long[] data = sortedRandomData(size) {
      scenario(params)
    }
    when: "add values to sketch"
    for (long value : data) {
      histogram.accept(value)
    }

    then: "have accurate quantiles"
    validateQuantiles(histogram, data)

    when: "perform serialization round trip"
    ByteBuffer buffer = histogram.serialize()
    def newHistogram = DDSketchProtoBinding.fromProto({
      new CollapsingLowestDenseStore(1024)
    }, DDSketch.parseFrom(buffer.array()))

    then: "quantiles accurate afterwards"
    validateQuantiles(newHistogram, data)

    where:
    scenario   |   size   | params
    poisson    |   10000  | [0.01D]
    poisson    |   100000 | [0.01D]
    poisson    |   10000  | [0.1D]
    poisson    |   100000 | [0.1D]
    poisson    |   10000  | [0.99D]
    poisson    |   100000 | [0.99D]
    uniform    |   10000  | [1D, 200D]
    uniform    |   100000 | [1D, 200D]
    uniform    |   10000  | [1000D, 2000D]
    uniform    |   100000 | [1000D, 2000D]
    normal     |   10000  | [1000D, 10D]
    normal     |   100000 | [1000D, 10D]
    normal     |   10000  | [10000D, 100D]
    normal     |   100000 | [10000D, 100D]
  }

  def "test serialization of empty histogram after clear"() {
    setup:
    def histogram = Histograms.newHistogram()
    when: "add values to sketch and clear"
    histogram.accept(1)
    histogram.accept(2)
    histogram.accept(3)
    histogram.clear()
    ByteBuffer serialized = histogram.serialize()
    then: "serialization succeeds and produces correct histogram"
    ByteBuffer buffer = DDSketchProtoBinding.fromProto({
      new CollapsingLowestDenseStore(1024)
    }, DDSketch.parseFrom(serialized.array())).serialize()
    buffer == serialized

    when: "add more values to sketch"
    histogram.accept(1)
    histogram.accept(2)
    histogram.accept(3)

    then: "histogram ok after another serialization round trip"
    def sketch = DDSketchProtoBinding.fromProto({
      new CollapsingLowestDenseStore(1024)
    }, DDSketch.parseFrom(histogram.serialize().array()))
    sketch.getCount() == 3
    (int)sketch.getMinValue() == 1
    (int)sketch.getMaxValue() == 3
  }

  def validateQuantiles(def histogram, long[] data) {
    for (double quantile : quantiles) {
      double relativeError = relativeError(histogram.getValueAtQuantile(quantile), empiricalQuantile(data, quantile))
      assert relativeError < 0.01
    }
    true
  }

  def relativeError(double value, double expected) {
    if (Math.abs(expected) < 1e-5) {
      return 0.0
    }
    return Math.abs(value - expected) / expected
  }

  double empiricalQuantile(long[] sortedData, double quantile) {
    return sortedData[(int) (quantile * sortedData.length)]
  }

  long[] sortedRandomData(int size, Closure<Double> distribution) {
    long[] data = new long[size]
    for (int i = 0; i < size; ++i) {
      data[i] = (long)distribution()
    }
    Arrays.sort(data)
    return data
  }
}
