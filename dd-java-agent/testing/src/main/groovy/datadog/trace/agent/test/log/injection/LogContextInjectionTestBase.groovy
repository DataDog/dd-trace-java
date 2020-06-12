package datadog.trace.agent.test.log.injection

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.Config
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.api.Config.GLOBAL_TAGS
import static datadog.trace.api.Config.PREFIX
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

/**
 * This class represents the standard test cases that new logging library integrations MUST
 * satisfy in order to support log injection.
 */
abstract class LogContextInjectionTestBase extends AgentTestRunner {

  /**
   * Set in the framework-specific context the given value at the given key
   */
  abstract put(String key, Object value)

  /**
   * Get from the framework-specific context the value at the given key
   */
  abstract get(String key)

  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.logs.injection", "true")
      System.setProperty(PREFIX + GLOBAL_TAGS, "env:test,version:0.1")
    }
  }

  def "Log context shows trace and span ids for active scope"() {
    when:
    put("foo", "bar")
    AgentSpan rootSpan = startSpan("root")
    AgentScope rootScope = activateSpan(rootSpan)

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == CorrelationIdentifier.getTraceId()
    get(CorrelationIdentifier.getSpanIdKey()) == CorrelationIdentifier.getSpanId()
    get("foo") == "bar"
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"

    when:
    AgentSpan childSpan = startSpan("child")
    AgentScope childScope = activateSpan(childSpan)

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == CorrelationIdentifier.getTraceId()
    get(CorrelationIdentifier.getSpanIdKey()) == CorrelationIdentifier.getSpanId()
    get("foo") == "bar"
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"

    when:
    childScope.close()
    childSpan.finish()

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == CorrelationIdentifier.getTraceId()
    get(CorrelationIdentifier.getSpanIdKey()) == CorrelationIdentifier.getSpanId()
    get("foo") == "bar"
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"

    when:
    rootScope.close()
    rootSpan.finish()

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == null
    get(CorrelationIdentifier.getSpanIdKey()) == null
    get("foo") == "bar"
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"
  }

  def "Log context is scoped by thread"() {
    AtomicReference<String> thread1TraceId = new AtomicReference<>()
    AtomicReference<String> thread2TraceId = new AtomicReference<>()

    final Thread thread1 = new Thread() {
      @Override
      void run() {
        // no trace in scope
        thread1TraceId.set(get(CorrelationIdentifier.getTraceIdKey()))
      }
    }

    final Thread thread2 = new Thread() {
      @Override
      void run() {
        // other trace in scope
        final AgentSpan thread2Span = startSpan("root2")
        final AgentScope thread2Scope = activateSpan(thread2Span)
        try {
          thread2TraceId.set(get(CorrelationIdentifier.getTraceIdKey()))
        } finally {
          thread2Scope.close()
          thread2Span.finish()
        }
      }
    }
    final AgentSpan mainSpan = startSpan("root")
    final AgentScope mainScope = activateSpan(mainSpan)
    thread1.start()
    thread2.start()
    final String mainThreadTraceId = get(CorrelationIdentifier.getTraceIdKey())
    final String expectedMainThreadTraceId = CorrelationIdentifier.getTraceId()

    thread1.join()
    thread2.join()

    expect:
    mainThreadTraceId == expectedMainThreadTraceId
    thread1TraceId.get() == null
    thread2TraceId.get() != null
    thread2TraceId.get() != mainThreadTraceId
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"

    cleanup:
    mainScope?.close()
    mainSpan?.finish()
  }

  def "always log service, version, env"() {
    expect:
    get(Tags.DD_SERVICE) == Config.DEFAULT_SERVICE_NAME
    get(Tags.DD_VERSION) == "0.1"
    get(Tags.DD_ENV) == "test"

  }
}
