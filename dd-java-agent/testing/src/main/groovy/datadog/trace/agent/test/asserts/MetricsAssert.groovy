package datadog.trace.agent.test.asserts

import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.common.sampling.RateByServiceSampler
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

class MetricsAssert {
  private final BigInteger spanParentId
  private final Map<String, Number> metrics
  private final Set<String> assertedMetrics = new TreeSet<>()

  private MetricsAssert(DDSpan span) {
    this.spanParentId = span.parentId
    this.metrics = span.metrics
  }

  static void assertMetrics(DDSpan span,
                            @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.MetricsAssert'])
                            @DelegatesTo(value = MetricsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def asserter = new MetricsAssert(span)
    def clone = (Closure) spec.clone()
    clone.delegate = asserter
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(asserter)
    asserter.assertMetricsAllVerified()
  }

  def defaultMetrics() {
    assertedMetrics.add(RateByServiceSampler.SAMPLING_AGENT_RATE)
    assertedMetrics.add(DDSpanContext.PRIORITY_SAMPLING_KEY)
    assertedMetrics.add(InstrumentationTags.DD_MEASURED)
  }

  def methodMissing(String name, args) {
    if (args.length == 0) {
      throw new IllegalArgumentException(args.toString())
    }
    def value = args[0]
    boolean asserted = true
    if (value instanceof Number) {
      assert metrics[name] == value
    } else if (value instanceof Closure) {
      assert ((Closure) value).call(metrics[name])
    } else if (value instanceof Class) {
      assert ((Class) value).isInstance(metrics[name])
    } else {
      asserted = false
    }
    if (asserted) {
      assertedMetrics.add(name)
    }
  }

  void assertMetricsAllVerified() {
    def set = new TreeMap<>(metrics).keySet()
    set.removeAll(assertedMetrics)
    // The primary goal is to ensure the set is empty.
    // tags and assertedTags are included via an "always true" comparison
    // so they provide better context in the error message.
    assert metrics.entrySet() != assertedMetrics && set.isEmpty()
  }
}
