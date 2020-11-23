import com.datadoghq.sketch.ddsketch.proto.DDSketch
import datadog.trace.core.histogram.DDSketchHistogram
import datadog.trace.core.histogram.Histogram
import datadog.trace.core.histogram.HistogramFactory
import datadog.trace.core.histogram.Histograms
import datadog.trace.core.histogram.StubHistogram
import datadog.trace.test.util.DDSpecification

class HistogramsTest extends DDSpecification {

  def "histogram factory creates DDSketch"() {
    expect:
    Histograms.newHistogramFactory().newHistogram() instanceof DDSketchHistogram
  }

  def "test serialize"() {
    setup:
    Histogram histogram = Histograms.newHistogramFactory().newHistogram()
    when:
    histogram.accept(42)
    byte[] serialized = histogram.serialize()
    DDSketch proto = DDSketch.parseFrom(serialized)
    then:
    null != proto
  }

  def "stub histogram creates empty bytes"() {
    setup:
    HistogramFactory histogramFactory = new StubHistogram()
    when:
    Histogram histogram = histogramFactory.newHistogram()
    histogram.accept(42)
    then:
    histogram.serialize().length == 0
  }

  def "load stub"() {
    setup:
    Histograms histograms = new Histograms(true)
    expect:
    histograms.newFactory().newHistogram() instanceof StubHistogram
  }
}
