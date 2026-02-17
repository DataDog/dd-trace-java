package datadog.metrics.api

import com.datadoghq.sketch.ddsketch.DDSketchProtoBinding
import com.datadoghq.sketch.ddsketch.proto.DDSketch
import com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore
import datadog.metrics.impl.DDSketchHistograms
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

  def "test quantiles have 1% relative error #iterationIndex"() {
    setup:
    def histogram

    if (relativeAccuracy == null) {
      histogram = DDSketchHistograms.FACTORY.newHistogram()
      relativeAccuracy = 0.01
    }
    else {
      histogram = DDSketchHistograms.FACTORY.newHistogram(relativeAccuracy, 1024)
    }

    long[] data = sortedRandomData(size) {
      scenario(params)
    }
    when: "add values to sketch"
    for (long value : data) {
      histogram.accept(value)
    }

    then: "have accurate quantiles"
    validateQuantiles(histogram, data, relativeAccuracy)

    when: "perform serialization round trip"
    ByteBuffer buffer = histogram.serialize()
    def newHistogram = DDSketchProtoBinding.fromProto({
      new CollapsingLowestDenseStore(1024)
    }, DDSketch.parseFrom(buffer.array()))

    then: "quantiles accurate afterwards"
    validateQuantiles(newHistogram, data, relativeAccuracy)

    where:
    scenario   |   size   | params          | relativeAccuracy
    poisson    |   10000  | [0.01D]         | null
    poisson    |   100000 | [0.01D]         | null
    poisson    |   10000  | [0.1D]          | null
    poisson    |   100000 | [0.1D]          | null
    poisson    |   10000  | [0.99D]         | null
    poisson    |   100000 | [0.99D]         | null
    uniform    |   10000  | [1D, 200D]      | null
    uniform    |   100000 | [1D, 200D]      | null
    uniform    |   10000  | [1000D, 2000D]  | null
    uniform    |   100000 | [1000D, 2000D]  | null
    normal     |   10000  | [1000D, 10D]    | null
    normal     |   100000 | [1000D, 10D]    | null
    normal     |   10000  | [10000D, 100D]  | null
    normal     |   100000 | [10000D, 100D]  | null
    poisson    |   10000  | [0.01D]         | 0.01
    poisson    |   100000 | [0.01D]         | 0.02
    poisson    |   10000  | [0.1D]          | 0.03
    poisson    |   100000 | [0.1D]          | 0.04
    poisson    |   10000  | [0.99D]         | 0.05
    poisson    |   100000 | [0.99D]         | 0.01
    uniform    |   10000  | [1D, 200D]      | 0.02
    uniform    |   100000 | [1D, 200D]      | 0.03
    uniform    |   10000  | [1000D, 2000D]  | 0.04
    uniform    |   100000 | [1000D, 2000D]  | 0.05
    normal     |   10000  | [1000D, 10D]    | 0.02
    normal     |   100000 | [1000D, 10D]    | 0.02
    normal     |   10000  | [10000D, 100D]  | 0.03
    normal     |   100000 | [10000D, 100D]  | 0.04
  }

  def "test serialization of empty histogram after clear"() {
    setup:
    def histogram = DDSketchHistograms.FACTORY.newHistogram()
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

  def "test explicit bin boundaries #iterationIndex"() {
    setup:
    def histogram = DDSketchHistograms.FACTORY.newHistogram(boundaries)
    def values = [
      Double.NEGATIVE_INFINITY,
      -Double.MAX_VALUE,
      -1d,
      -Double.MIN_VALUE,
      -0d,
      Double.NaN,
      0d,
      Double.MIN_VALUE,
      1d,
      11d,
      20d,
      25d,
      Double.MAX_VALUE,
      Double.POSITIVE_INFINITY
    ]

    when: "add values to sketch"
    for (long value : values) {
      histogram.accept(value)
    }

    then: "report boundaries and counts"
    histogram.getBinBoundaries() == countBoundaries
    histogram.getBinCounts() == countValues

    where:
    boundaries                         | countBoundaries                                    | countValues
    []                                 | [Double.POSITIVE_INFINITY]                         | [14d]
    [0d]                               | [0d, Double.POSITIVE_INFINITY]                     | [8d, 6d]
    [0d, 1d]                           | [0d, 1d, Double.POSITIVE_INFINITY]                 | [8d, 1d, 5d]
    [0d, 25d, 50d]                     | [0d, 25d, 50d, Double.POSITIVE_INFINITY]           | [8d, 4d, 0d, 2d]
    [0d, 5d, 10d, 25d, 50d, 75d, 100d] | [0d, 5d, 10d, 25d, 100d, Double.POSITIVE_INFINITY] | [8d, 1d, 0d, 3d, 0d, 2d]
  }

  def "test invalid bin boundaries are reported #iterationIndex"() {
    when:
    DDSketchHistograms.FACTORY.newHistogram(boundaries)

    then:
    def e = thrown IllegalArgumentException
    e.message == message

    where:
    boundaries                 | message
    [-1d]                      | "invalid bucket boundary: -1.0"
    [null]                     | "invalid bucket boundary: null"
    [Double.NaN]               | "invalid bucket boundary: NaN"
    [Double.NEGATIVE_INFINITY] | "invalid bucket boundary: -Infinity"
    [Double.POSITIVE_INFINITY] | "invalid bucket boundary: +Infinity"
    [1d, 2d, 3d, 3d, 4d, 5d]   | "Bucket boundaries must be in increasing order: 3.0 >= 3.0"
    [1d, 2d, 3d, 4d, 3d]       | "Bucket boundaries must be in increasing order: 4.0 >= 3.0"
  }

  def validateQuantiles(def histogram, long[] data, double relativeAccuracy) {
    for (double quantile : quantiles) {
      double relativeError = relativeError(histogram.getValueAtQuantile(quantile), empiricalQuantile(data, quantile))
      assert relativeError < (relativeAccuracy + 1E-12)
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
