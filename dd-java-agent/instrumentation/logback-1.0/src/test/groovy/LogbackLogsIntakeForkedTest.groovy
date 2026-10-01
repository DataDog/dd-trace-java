import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.logging.intake.LogsIntake
import datadog.trace.api.logging.intake.LogsWriter
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Shared

import java.util.concurrent.CopyOnWriteArrayList

abstract class LogbackLogsIntakeTestBase extends InstrumentationSpecification {
  static final List<Map<String, Object>> MESSAGES = new CopyOnWriteArrayList<>()
  @Shared
  def app

  boolean agentless() {
    true
  }
  boolean appLogs() {
    false
  }
  boolean injection() {
    true
  }
  boolean integration() {
    true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("agentless.log.submission.enabled", agentless().toString())
    injectSysConfig("app.logs.collection.enabled", appLogs().toString())
    injectSysConfig("logs.injection.enabled", injection().toString())
    injectSysConfig("integration.logback.enabled", integration().toString())
    injectSysConfig("agentless.log.submission.level", "WARN")
  }

  def setupSpec() {
    app = new LogbackTestClassLoader(getClass().classLoader).loadClass("LogbackTestApplication").getDeclaredConstructor().newInstance()
    LogsIntake.registerWriter(new LogsWriter() {
        void log(Map<String, Object> message) {
          if (message.loggerName == "test.logback.intake") {
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
    app.clear()
    app.contexts.clear()
  }

  def cleanup() {
    app.clear()
  }

  def "flags preserve opt-in, app-log precedence and framework filtering"() {
    when:
    app.log("DEBUG", "debug")
    app.log("INFO", "info")
    app.log("WARN", "formatted message")
    app.log("ERROR", "error")

    then:
    MESSAGES*.message == (!integration() || (!agentless() && !appLogs()) ? [] :
    appLogs() ? ["debug", "info", "formatted message", "error"] : ["formatted message", "error"])
    if (!MESSAGES.empty) {
      assert MESSAGES.last().thrown.name == IllegalStateException.name
      assert MESSAGES.last().thrown.message == "failure"
      assert MESSAGES.last().thrown.extendedStackTrace
      assert MESSAGES.every { it.containsKey("contextMap") == !appLogs() }
      assert MESSAGES.every { it.containsKey("timestamp") == !appLogs() }
    }
  }

  def "producer correlation honors injection switch and snapshots MDC before wrapper cleanup"() {
    setup:
    def span = AgentTracer.startSpan("test", "logback-producer")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId
    app.put("custom", "before-clear")

    when:
    app.log("WARN", "active")
    app.put("dd.trace_id", "user-trace")
    app.log("WARN", "collision")
    app.clear()
    scope.close()
    span.finish()
    app.log("WARN", "outside")

    then:
    app.contexts.size() == 3
    app.contexts[0]["dd.trace_id"] == (integration() && injection() ? traceId : null)
    app.contexts[0]["dd.span_id"] == (integration() && injection() ? spanId : null)
    app.contexts[1]["dd.trace_id"] == "user-trace"
    !app.contexts[2].containsKey("dd.trace_id")
    if (integration() && (agentless() || appLogs())) {
      assert MESSAGES.size() == 3
      def first = appLogs() ? MESSAGES[0] : MESSAGES[0].contextMap
      assert first["dd.trace_id"] == (appLogs() || injection() ? traceId : null)
      assert first["dd.span_id"] == (appLogs() || injection() ? spanId : null)
      if (!appLogs()) {
        assert first.custom == "before-clear"
        assert MESSAGES[1].contextMap["dd.trace_id"] == "user-trace"
        assert !MESSAGES[2].contextMap.containsKey("dd.trace_id")
      } else {
        assert MESSAGES[1]["dd.trace_id"] == traceId
        assert !MESSAGES[2].containsKey("dd.trace_id")
      }
    } else {
      assert MESSAGES.empty
    }
  }
}

class LogbackLogsIntakeForkedTest extends LogbackLogsIntakeTestBase {
  def "async delivery after scope closes retains producer IDs exactly once"() {
    setup:
    app.startAsync()
    def span = AgentTracer.startSpan("test", "async-producer")
    def scope = AgentTracer.activateSpan(span)
    def traceId = CorrelationIdentifier.traceId
    def spanId = CorrelationIdentifier.spanId

    when:
    app.log("WARN", "async")
    scope.close()
    span.finish()
    def deliveredContext = app.finishAsync()

    then:
    MESSAGES.size() == 1
    MESSAGES[0].contextMap["dd.trace_id"] == traceId
    MESSAGES[0].contextMap["dd.span_id"] == spanId
    deliveredContext["dd.span_id"] == spanId
  }

  def "JUL bridge reaches the same backend hook once"() {
    when:
    app.jul()

    then:
    MESSAGES*.message == ["bridged"]
  }
}

class LogbackNoSubmissionForkedTest extends LogbackLogsIntakeTestBase {
  boolean agentless() {
    false
  }
}

class LogbackAppLogsForkedTest extends LogbackLogsIntakeTestBase {
  boolean agentless() {
    false
  }
  boolean appLogs() {
    true
  }
}

class LogbackBothFlagsForkedTest extends LogbackLogsIntakeTestBase {
  boolean appLogs() {
    true
  }
}

class LogbackNoInjectionForkedTest extends LogbackLogsIntakeTestBase {
  boolean injection() {
    false
  }
}

class LogbackDisabledForkedTest extends LogbackLogsIntakeTestBase {
  boolean integration() {
    false
  }
}

class LogbackCiAgentlessForkedTest extends LogbackLogsIntakeTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}

class LogbackCiCorrelationForkedTest extends LogbackNoSubmissionForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
  }
}

/** The harness boot-loads its logger; exercise a separate application-loaded backend. */
@groovy.transform.CompileStatic
class LogbackTestClassLoader extends ClassLoader {
  LogbackTestClassLoader(ClassLoader parent) {
    super(parent)
  }

  @Override
  protected synchronized Class<?> loadClass(String name, boolean resolve) {
    if (!name.startsWith("ch.qos.logback.") && !name.startsWith("org.slf4j.") && !name.startsWith("LogbackTestApplication")) {
      return super.loadClass(name, resolve)
    }
    def loaded = findLoadedClass(name)
    if (loaded == null) {
      byte[] bytes = getResourceAsStream(name.replace('.', '/') + '.class').withCloseable { it.bytes }
      loaded = defineClass(name, bytes, 0, bytes.length)
    }
    if (resolve) {
      resolveClass(loaded)
    }
    return loaded
  }
}
