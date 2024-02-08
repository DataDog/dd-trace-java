package datadog.trace.core


import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

class BlackholeSpanTest extends DDCoreSpecification {
  def "should mute tracing"() {
    setup:
    injectSysConfig("trace.128.bit.traceid.logging.enabled", moreBits)
    def writer = new ListWriter()
    def props = new Properties()
    def tracer = tracerBuilder().withProperties(props).writer(writer).build()
    when:
    def child = null
    def bh = null
    def ignored = null
    def root = tracer.startSpan("test", "root")
    def scope1 = tracer.activateSpan(root)
    try {
      bh = tracer.blackholeSpan()
      def scope2 = tracer.activateSpan(bh)
      try {
        ignored = tracer.startSpan("test", "ignored")
        ignored.finish()
      } finally {
        bh.finish()
        scope2.close()
      }
      child = tracer.startSpan("test", "child")
      child.finish()
    } finally {
      root.finish()
      scope1.close()
    }
    then:
    writer.waitForTraces(1)
    assert writer.firstTrace().size() == 2
    assert writer.firstTrace().containsAll([root, child])
    assert !writer.firstTrace().contains(bh)
    assert !writer.firstTrace().contains(ignored)

    cleanup:
    writer.close()
    tracer.close()
    where:
    moreBits | _
    "true"   | _
    "false"  | _
  }
}
