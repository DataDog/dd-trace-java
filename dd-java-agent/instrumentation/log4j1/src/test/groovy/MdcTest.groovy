import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.AgentTestRunner
import org.apache.log4j.Category
import org.apache.log4j.MDC
import org.apache.log4j.Priority
import org.apache.log4j.spi.LoggingEvent
import spock.lang.Requires

// Log4j 1.x reached EOL in 2015 and is not compatible with Java 25+
// https://endoflife.date/log4j
class MdcTest extends AgentTestRunner {
  @Requires({
    !JavaVirtualMachine.isJavaVersionAtLeast(25)
  })
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
    injectionEnabled << ["true", "false"]
  }

  def "should prevent NPE when MDC context doesn't exist and getMDCCopy"() {
    setup:
    injectSysConfig("logs.injection", "true")
    def event = new LoggingEvent("test", Category.getRoot(), Priority.INFO, "hello world", null)
    when:
    event.getMDCCopy()
    then:
    noExceptionThrown()
  }
}
