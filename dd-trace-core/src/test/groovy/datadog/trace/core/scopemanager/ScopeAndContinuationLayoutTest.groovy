package datadog.trace.core.scopemanager

import datadog.trace.test.util.DDSpecification
import org.openjdk.jol.info.ClassLayout
import spock.lang.Requires

@Requires({!System.getProperty("java.vendor").toUpperCase().contains("IBM")})
class ScopeAndContinuationLayoutTest extends DDSpecification {

  def "continuable scope layout"() {
    expect:
    layoutAcceptable(ContinuableScopeManager.ContinuableScope, 32)
  }

  def "single continuation layout"() {
    expect: layoutAcceptable(ContinuableScopeManager.SingleContinuation, 32)
  }

  def "concurrent continuation layout"() {
    expect: layoutAcceptable(ContinuableScopeManager.ConcurrentContinuation, 32)
  }

  def layoutAcceptable(Class<?> klass, int acceptableSize) {
    def layout = ClassLayout.parseClass(klass)
    System.err.println(layout.toPrintable())
    return layout.instanceSize() <= acceptableSize
  }
}
