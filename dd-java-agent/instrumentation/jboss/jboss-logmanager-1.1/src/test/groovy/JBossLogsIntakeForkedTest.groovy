import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import org.jboss.logmanager.ExtLogRecord
import org.jboss.logmanager.Level
import org.jboss.logmanager.LogContext
import org.jboss.logmanager.Logger
import org.jboss.logmanager.MDC
import org.jboss.logmanager.handlers.AsyncHandler

abstract class JBossLogsIntakeTestBase extends InstrumentationSpecification {
  static final String LOGGER_NAME = "test.jboss.intake"
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>()

  static final LogContext LOG_CONTEXT = LogContext.create()

  Logger parent = LOG_CONTEXT.getLogger(LOGGER_NAME)
  Logger logger = LOG_CONTEXT.getLogger(LOGGER_NAME + ".child")
  ContextHandler handler = new ContextHandler()

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
    MDC.clear()
    parent.setUseParentHandlers(false)
    parent.setLevel(Level.TRACE)
    logger.setLevel(Level.TRACE)
    logger.addHandler(handler)
  }

  def cleanup() {
    MDC.clear()
    logger.removeHandler(handler)
    parent.clearHandlers()
  }

  def "submits filtered events with mapped levels and exceptions"() {
    when:
    logger.log(Level.DEBUG, "debug")
    logger.log(Level.INFO, "info")
    logger.log(Level.WARN, "warn")
    logger.log(java.util.logging.Level.WARNING, "jul {0}", "warning")
    logger.log(Level.ERROR, "error", new IllegalStateException("failure"))
    logger.log(Level.FATAL, "fatal")

    then:
    handler.contexts.size() == 6
    if (agentless()) {
      assert MESSAGES*.message == ["warn", "jul warning", "error", "fatal"]
      assert MESSAGES*.level == ["WARN", "WARN", "ERROR", "FATAL"]
      assert MESSAGES.every {
        it.loggerName == logger.name && it.thread == Thread.currentThread().name && it.timestamp instanceof Long
      }
      def thrown = MESSAGES[2].thrown
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
    def span = AgentTracer.startSpan("test", "jboss-producer")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId
    MDC.put("custom", "value")

    when:
    logger.log(Level.WARN, "active")
    def child = AgentTracer.startSpan("test", "jboss-child")
    def childScope = AgentTracer.activateSpan(child)
    def childSpanId = CorrelationIdentifier.spanId
    logger.log(Level.WARN, "child")
    childScope.close()
    child.finish()
    MDC.put("dd.trace_id", "user-trace")
    logger.log(Level.WARN, "collision")
    MDC.clear()
    scope.close()
    span.finish()
    logger.log(Level.WARN, "outside")

    then:
    handler.contexts[0]["dd.trace_id"] == (injection() ? traceId : null)
    handler.contexts[0]["dd.span_id"] == (injection() ? spanId : null)
    handler.contexts[0]["custom"] == "value"
    if (agentless()) {
      assert MESSAGES*.message == ["active", "child", "collision", "outside"]
      def contexts = MESSAGES*.contextMap
      assert contexts[0] == (injection() ? ["dd.trace_id": traceId, "dd.span_id": spanId, custom: "value"] : [custom: "value"])
      assert contexts[1]["dd.span_id"] == (injection() ? childSpanId : null)
      assert contexts[1]["dd.trace_id"] == (injection() ? traceId : null)
      assert contexts[2]["dd.trace_id"] == "user-trace"
      assert contexts[3] == [:]
    } else {
      assert MESSAGES.empty
    }
  }

  def "submits once when the event reaches parent handlers"() {
    setup:
    def parentHandler = new ContextHandler()
    parent.addHandler(parentHandler)

    when:
    logger.log(Level.WARN, "propagated")

    then:
    handler.contexts.size() == 1
    parentHandler.contexts.size() == 1
    MESSAGES*.message == (agentless() ? ["propagated"] : [])
  }

  def "submits once with producer context when delivery is asynchronous"() {
    setup:
    def delivered = new CountDownLatch(1)
    def release = new CountDownLatch(1)
    def asyncTarget = new ContextHandler() {
        @Override
        void publish(LogRecord record) {
          release.await(5, TimeUnit.SECONDS)
          super.publish(record)
          delivered.countDown()
        }
      }
    def async = new AsyncHandler()
    async.addHandler(asyncTarget)
    logger.removeHandler(handler)
    logger.addHandler(async)
    def span = AgentTracer.startSpan("test", "jboss-async")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId

    when:
    logger.log(Level.WARN, "async")
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

    cleanup:
    logger.removeHandler(async)
    async.close()
  }

  static class ContextHandler extends Handler {
    final List<Map<String, String>> contexts = new CopyOnWriteArrayList<>()

    @Override
    void publish(LogRecord record) {
      def extRecord = (ExtLogRecord) record
      contexts.add([
        "dd.trace_id": extRecord.getMdc("dd.trace_id"),
        "dd.span_id" : extRecord.getMdc("dd.span_id"),
        "custom"     : extRecord.getMdc("custom")
      ])
    }

    @Override
    void flush() {}

    @Override
    void close() {}
  }
}

class JBossLogsIntakeForkedTest extends JBossLogsIntakeTestBase {}

class JBossNoSubmissionForkedTest extends JBossLogsIntakeTestBase {
  boolean agentless() {
    false
  }
}

class JBossNoInjectionForkedTest extends JBossLogsIntakeTestBase {
  boolean injection() {
    false
  }
}

class JBossCiAgentlessForkedTest extends JBossLogsIntakeTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}

class JBossCiCorrelationForkedTest extends JBossNoSubmissionForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}
