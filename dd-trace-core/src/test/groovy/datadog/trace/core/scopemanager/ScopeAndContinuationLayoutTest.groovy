package datadog.trace.core.scopemanager

import datadog.trace.test.util.DDSpecification
import org.openjdk.jol.info.ClassLayout
import spock.lang.Requires

@Requires({
  !System.getProperty("java.vendor").toUpperCase().contains("IBM")
})
class ScopeAndContinuationLayoutTest extends DDSpecification {

  def "continuable scope layout"() {
    expect: layoutAcceptable(ContinuableScope, 32)
  }

  def "single continuation layout"() {
    expect: layoutAcceptable(ScopeContinuation, 32)
  }

  def layoutAcceptable(Class<?> klass, int acceptableSize) {
    def layout = ClassLayout.parseClass(klass)
    System.err.println(layout.toPrintable())
    return layout.instanceSize() <= acceptableSize
  }
}
