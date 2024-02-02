import datadog.trace.agent.test.AgentTestRunner
import org.apache.log4j.Category
import org.apache.log4j.MDC
import org.apache.log4j.Priority
import org.apache.log4j.spi.LoggingEvent
import spock.lang.Unroll

@Unroll
class MdcTest extends AgentTestRunner {
  def "should preserve mdc when logging injection is #injectionEnabled"() {
    setup:
    injectSysConfig("logs.injection", injectionEnabled)
    def event = new LoggingEvent("test", Category.getRoot(), Priority.INFO, "hello world", null)
    when:
    MDC.put("data", "dog")
    event.getMDCCopy()
    MDC.remove("data")
    then:
    assert event.getMDC("data") == "dog"
    where:
    injectionEnabled | _
    "true"           | _
    "false"          | _
  }
}
