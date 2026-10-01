import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.MDC
import org.apache.log4j.spi.LoggingEvent

abstract class Log4j1LogsIntakeTestBase extends InstrumentationSpecification {
  static final String LOGGER_NAME = "test.log4j1.intake"
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>()

  Logger parent = Logger.getLogger(LOGGER_NAME)
  Logger logger = Logger.getLogger(LOGGER_NAME + ".child")
  ContextAppender appender = new ContextAppender()

  boolean agentless() {
    true
  }

  boolean injection() {
    true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("agentless.log.submission.enabled", agentless().toString())
    injectSysConfig("logs.injection.enabled", injection().toString())
    injectSysConfig("agentless.log.submission.level", "WARN")
  }

  def setupSpec() {
    LogsIntake.registerWriter(new LogsWriter() {
        void log(Map<String, Object> message) {
          if (message.loggerName.toString().startsWith(LOGGER_NAME)) {
            MESSAGES.add(message)
          }
        }

        void start() {}

        void shutdown() {}
      })
  }

  def cleanupSpec() {
    LogsIntake.registerWriter(null)
  }

  def setup() {
    MESSAGES.clear()
    MDC.remove("custom")
    MDC.remove("dd.trace_id")
    parent.setAdditivity(false)
    parent.setLevel(Level.DEBUG)
    logger.setLevel(Level.DEBUG)
    logger.addAppender(appender)
  }

  def cleanup() {
    MDC.remove("custom")
    MDC.remove("dd.trace_id")
    logger.removeAllAppenders()
    parent.removeAllAppenders()
  }

  def "submits filtered events with levels and exceptions"() {
    when:
    logger.debug("debug")
    logger.info("info")
    logger.warn("warn")
    logger.error("error", new IllegalStateException("failure"))
    logger.fatal("fatal")
    logger.setLevel(Level.FATAL)
    logger.error("below logger level")

    then:
    appender.contexts.size() == 5
    if (agentless()) {
      assert MESSAGES*.message == ["warn", "error", "fatal"]
      assert MESSAGES*.level == ["WARN", "ERROR", "FATAL"]
      assert MESSAGES.every {
        it.loggerName == logger.name && it.thread == Thread.currentThread().name && it.timestamp instanceof Long
      }
      def thrown = MESSAGES[1].thrown
      assert thrown.name == IllegalStateException.name
      assert thrown.message == "failure"
      assert thrown.extendedStackTrace.contains("IllegalStateException: failure")
      assert !MESSAGES[0].containsKey("thrown")
    } else {
      assert MESSAGES.empty
    }
  }

  def "submits producer context and preserves user MDC"() {
    setup:
    def span = AgentTracer.startSpan("test", "log4j1-producer")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId
    MDC.put("custom", "value")

    when:
    logger.warn("active")
    def child = AgentTracer.startSpan("test", "log4j1-child")
    def childScope = AgentTracer.activateSpan(child)
    def childSpanId = CorrelationIdentifier.spanId
    logger.warn("child")
    childScope.close()
    child.finish()
    MDC.put("dd.trace_id", "user-trace")
    logger.warn("collision")
    MDC.remove("dd.trace_id")
    MDC.remove("custom")
    scope.close()
    span.finish()
    logger.warn("outside")

    then:
    appender.contexts[0]["dd.trace_id"] == (injection() ? traceId : null)
    appender.contexts[0]["dd.span_id"] == (injection() ? spanId : null)
    appender.contexts[0]["custom"] == "value"
    if (agentless()) {
      assert MESSAGES*.message == ["active", "child", "collision", "outside"]
      def contexts = MESSAGES*.contextMap
      assert contexts[0] == (injection() ? ["dd.trace_id": traceId, "dd.span_id": spanId, custom: "value"] : [custom: "value"])
      assert contexts[1]["dd.trace_id"] == (injection() ? traceId : null)
      assert contexts[1]["dd.span_id"] == (injection() ? childSpanId : null)
      assert contexts[2]["dd.trace_id"] == "user-trace"
      assert contexts[3] == [:]
    } else {
      assert MESSAGES.empty
    }
  }

  def "submits once when the event reaches parent appenders"() {
    setup:
    def parentAppender = new ContextAppender()
    parent.addAppender(parentAppender)

    when:
    logger.warn("propagated")

    then:
    appender.contexts.size() == 1
    parentAppender.contexts.size() == 1
    MESSAGES*.message == (agentless() ? ["propagated"] : [])
  }

  def "submits once with producer context when delivery is asynchronous"() {
    setup:
    def release = new CountDownLatch(1)
    def delivered = new CountDownLatch(1)
    def asyncTarget = new ContextAppender()
    // AsyncAppender's dispatcher thread is globally ignored by the instrumentation test harness
    def async = new AppenderSkeleton() {
        @Override
        protected void append(LoggingEvent event) {
          event.getMDCCopy()
          Thread.start {
            release.await(5, TimeUnit.SECONDS)
            asyncTarget.doAppend(event)
            delivered.countDown()
          }
        }

        @Override
        void close() {}

        @Override
        boolean requiresLayout() {
          return false
        }
      }
    logger.removeAllAppenders()
    logger.addAppender(async)
    def span = AgentTracer.startSpan("test", "log4j1-async")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId

    when:
    logger.warn("async")
    scope.close()
    span.finish()
    release.countDown()

    then:
    delivered.await(5, TimeUnit.SECONDS)
    asyncTarget.contexts[0]["dd.span_id"] == (injection() ? spanId : null)
    if (agentless()) {
      assert MESSAGES.size() == 1
      assert MESSAGES[0].contextMap["dd.trace_id"] == (injection() ? traceId : null)
      assert MESSAGES[0].contextMap["dd.span_id"] == (injection() ? spanId : null)
    } else {
      assert MESSAGES.empty
    }
  }

  static class ContextAppender extends AppenderSkeleton {
    final List<Map<String, Object>> contexts = new CopyOnWriteArrayList<>()

    @Override
    protected void append(LoggingEvent event) {
      contexts.add([
        "dd.trace_id": event.getMDC("dd.trace_id"),
        "dd.span_id" : event.getMDC("dd.span_id"),
        custom       : event.getMDC("custom")
      ])
    }

    @Override
    void close() {}

    @Override
    boolean requiresLayout() {
      return false
    }
  }
}

class Log4j1LogsIntakeForkedTest extends Log4j1LogsIntakeTestBase {}

class Log4j1NoSubmissionForkedTest extends Log4j1LogsIntakeTestBase {
  boolean agentless() {
    false
  }
}

class Log4j1NoInjectionForkedTest extends Log4j1LogsIntakeTestBase {
  boolean injection() {
    false
  }
}

class Log4j1CiAgentlessForkedTest extends Log4j1LogsIntakeTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}

class Log4j1CiCorrelationForkedTest extends Log4j1NoSubmissionForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}
