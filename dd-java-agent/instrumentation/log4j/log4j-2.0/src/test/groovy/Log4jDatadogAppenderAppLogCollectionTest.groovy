import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.junit.jupiter.api.Assumptions
import spock.util.environment.Jvm

class Log4jDatadogAppenderAppLogCollectionTest extends InstrumentationSpecification {

  private static DummyLogsWriter logsWriter

  def setupSpec() {
    logsWriter = new DummyLogsWriter()
    LogsIntake.registerWriter(logsWriter)
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(GeneralConfig.APP_LOGS_COLLECTION_ENABLED, "true")
  }

  def "test datadog appender registration"() {
    setup:
    ensureLog4jVersionCompatibleWithCurrentJVM()

    def logger = LogManager.getLogger(Log4jDatadogAppenderAppLogCollectionTest)

    when:
    logger.error("A test message")

    then:
    !logsWriter.messages.empty

    def message = logsWriter.messages.poll()
    "A test message" == message.get("message")
    "ERROR" == message.get("level")
    "Log4jDatadogAppenderAppLogCollectionTest" == message.get("loggerName")
  }

  private static ensureLog4jVersionCompatibleWithCurrentJVM() {
    try {
      // init class to see if UnsupportedClassVersionError gets thrown
      AbstractAppender.package
    } catch (UnsupportedClassVersionError e) {
      Assumptions.assumeTrue(false, "Latest Log4j2 release requires Java 17, current JVM: " + Jvm.current.javaVersion)
    }
  }

  private static final class DummyLogsWriter implements LogsWriter {
    private final Queue<Map<String, Object>> messages = new ArrayDeque<>()

    @Override
    void log(Map<String, Object> message) {
      messages.offer(message)
    }

    @Override
    void start() {
      // no op
    }

    @Override
    void shutdown() {
      // no op
    }
  }
}
