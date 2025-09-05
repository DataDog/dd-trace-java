import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.junit.jupiter.api.Assumptions
import spock.util.environment.Jvm

import java.nio.file.Files
import java.nio.file.Path

class Log4jDatadogAppenderTest extends InstrumentationSpecification {

  private static Path agentKeyFile
  private static DummyLogsWriter logsWriter

  def setupSpec() {
    logsWriter = new DummyLogsWriter()
    LogsIntake.registerWriter(logsWriter)
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    agentKeyFile = Files.createTempFile("LogIntakeTest", "dummy_agent_key")
    Files.write(agentKeyFile, "dummy".getBytes())

    injectSysConfig(GeneralConfig.API_KEY_FILE, agentKeyFile.toString())
    injectSysConfig(GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED, "true")
  }

  def cleanupSpec() {
    Files.deleteIfExists(agentKeyFile)
  }

  def "test datadog appender registration"() {
    setup:
    ensureLog4jVersionCompatibleWithCurrentJVM()

    def logger = LogManager.getLogger(Log4jDatadogAppenderTest)

    when:
    logger.error("A test message")

    then:
    !logsWriter.messages.empty

    def message = logsWriter.messages.poll()
    "A test message" == message.get("message")
    "ERROR" == message.get("level")
    "Log4jDatadogAppenderTest" == message.get("loggerName")
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
