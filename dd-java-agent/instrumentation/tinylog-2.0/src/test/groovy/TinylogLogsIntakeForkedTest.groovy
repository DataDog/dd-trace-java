import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.instrumentation.tinylog2.ContextWriter
import java.util.concurrent.CopyOnWriteArrayList
import org.tinylog.Logger
import org.tinylog.ThreadContext
import spock.util.concurrent.PollingConditions

abstract class TinylogLogsIntakeTestBase extends InstrumentationSpecification {
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>()

  boolean agentless() {
    true
  }

  boolean injection() {
    true
  }

  boolean writingThread() {
    false
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("agentless.log.submission.enabled", agentless().toString())
    injectSysConfig("logs.injection.enabled", injection().toString())
    injectSysConfig("agentless.log.submission.level", "WARN")
    System.setProperty("tinylog.level", "trace")
    System.setProperty("tinylog.writingthread", writingThread().toString())
    System.setProperty("tinylog.writer1", ContextWriter.name)
    System.setProperty("tinylog.writer2", ContextWriter.name)
  }

  def setupSpec() {
    LogsIntake.registerWriter(new LogsWriter() {
        void log(Map<String, Object> message) {
          MESSAGES.add(message)
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
    ContextWriter.CONTEXTS.clear()
    ThreadContext.clear()
  }

  def cleanup() {
    ThreadContext.clear()
  }

  List<Map<String, String>> writtenContexts(int count) {
    new PollingConditions(timeout: 5).eventually {
      assert ContextWriter.CONTEXTS.size() == count * 2
    }
    assert ContextWriter.CONTEXTS.every {
      (it.thread != Thread.currentThread().name) == writingThread()
    }
    // Both writers receive the same entries
    return ContextWriter.CONTEXTS.findAll { it.writer == "0" }
  }

  def "submits filtered events once with levels, tags and exceptions"() {
    when:
    Logger.debug("debug")
    Logger.info("info")
    Logger.warn("warn {}", "formatted")
    Logger.tag("intake").warn("tagged")
    Logger.error(new IllegalStateException("failure"), "error")

    then:
    writtenContexts(5).size() == 5
    if (agentless()) {
      assert MESSAGES*.message == ["warn formatted", "tagged", "error"]
      assert MESSAGES*.level == ["WARN", "WARN", "ERROR"]
      assert MESSAGES*.loggerName == [null, "intake", null]
      assert MESSAGES.every {
        it.thread == Thread.currentThread().name && it.timestamp instanceof Long
      }
      def thrown = MESSAGES[2].thrown
      assert thrown.name == IllegalStateException.name
      assert thrown.message == "failure"
      assert thrown.extendedStackTrace.contains("IllegalStateException: failure")
    } else {
      assert MESSAGES.empty
    }
  }

  def "submits producer context and preserves user context"() {
    setup:
    def span = AgentTracer.startSpan("test", "tinylog-producer")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId
    ThreadContext.put("custom", "value")

    when:
    Logger.warn("active")
    def child = AgentTracer.startSpan("test", "tinylog-child")
    def childScope = AgentTracer.activateSpan(child)
    def childSpanId = CorrelationIdentifier.spanId
    Logger.warn("child")
    childScope.close()
    child.finish()
    ThreadContext.put("dd.trace_id", "user-trace")
    Logger.warn("collision")
    ThreadContext.clear()
    scope.close()
    span.finish()
    Logger.warn("outside")
    def contexts = writtenContexts(4)

    then:
    contexts[0]["dd.trace_id"] == (injection() ? traceId : null)
    contexts[0]["dd.span_id"] == (injection() ? spanId : null)
    contexts[0]["custom"] == "value"
    contexts[1]["dd.span_id"] == (injection() ? childSpanId : null)
    contexts[3]["dd.trace_id"] == null
    if (agentless()) {
      assert MESSAGES*.message == ["active", "child", "collision", "outside"]
      def submitted = MESSAGES*.contextMap
      assert submitted[0] == (injection() ? ["dd.trace_id": traceId, "dd.span_id": spanId, custom: "value"] : [custom: "value"])
      assert submitted[1]["dd.trace_id"] == (injection() ? traceId : null)
      assert submitted[1]["dd.span_id"] == (injection() ? childSpanId : null)
      assert submitted[2]["dd.trace_id"] == "user-trace"
      assert submitted[3] == [:]
    } else {
      assert MESSAGES.empty
    }
  }
}

class TinylogLogsIntakeForkedTest extends TinylogLogsIntakeTestBase {}

class TinylogWritingThreadForkedTest extends TinylogLogsIntakeTestBase {
  boolean writingThread() {
    true
  }
}

class TinylogNoSubmissionForkedTest extends TinylogLogsIntakeTestBase {
  boolean agentless() {
    false
  }
}

class TinylogNoInjectionForkedTest extends TinylogLogsIntakeTestBase {
  boolean injection() {
    false
  }
}

class TinylogCiAgentlessForkedTest extends TinylogLogsIntakeTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}

class TinylogCiCorrelationForkedTest extends TinylogNoSubmissionForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}
