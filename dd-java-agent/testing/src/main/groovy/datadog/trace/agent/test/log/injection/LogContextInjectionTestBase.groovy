package datadog.trace.agent.test.log.injection

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags

import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.api.config.GeneralConfig.TAGS
import static datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource.PREFIX
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

/**
 * This class represents the standard test cases that new logging library integrations MUST
 * satisfy in order to support log injection.
 */
abstract class LogContextInjectionTestBase extends AgentTestRunner {
  private static final TEST_ENV = "test"
  private static final TEST_VERSION = "0.1"

  /**
   * Set in the framework-specific context the given value at the given key
   */
  abstract put(String key, Object value)

  /**
   * Get from the framework-specific context the value at the given key
   */
  abstract get(String key)

  /**
   * Remove from the framework-specific context the value at the given key
   */
  abstract remove(String key)

  abstract clear()

  abstract getMap()

  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.logs.injection", "true")
      System.setProperty("dd.logs.mdc.tags.injection", "true")
      System.setProperty(PREFIX + TAGS, "env:${TEST_ENV},version:${TEST_VERSION}")
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
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV

    when:
    AgentSpan childSpan = startSpan("child")
    AgentScope childScope = activateSpan(childSpan)

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == CorrelationIdentifier.getTraceId()
    get(CorrelationIdentifier.getSpanIdKey()) == CorrelationIdentifier.getSpanId()
    get("foo") == "bar"
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV

    when:
    childScope.close()
    childSpan.finish()

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == CorrelationIdentifier.getTraceId()
    get(CorrelationIdentifier.getSpanIdKey()) == CorrelationIdentifier.getSpanId()
    get("foo") == "bar"
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV

    when:
    rootScope.close()
    rootSpan.finish()

    then:
    get(CorrelationIdentifier.getTraceIdKey()) == null
    get(CorrelationIdentifier.getSpanIdKey()) == null
    get("foo") == "bar"
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV
  }

  def "Log context is scoped by thread"() {
    AtomicReference<String> thread1TraceId = new AtomicReference<>()
    AtomicReference<String> thread2TraceId = new AtomicReference<>()
    def t1VersionBeg
    def t1VersionEnd
    def t2VersionBeg
    def t2VersionEnd
    def t1EnvBeg
    def t1EnvEnd
    def t2EnvBeg
    def t2EnvEnd

    final Thread thread1 = new Thread() {
      @Override
      void run() {
        t1VersionBeg = get(Tags.DD_VERSION)
        t1EnvBeg = get(Tags.DD_ENV)
        // no trace in scope
        thread1TraceId.set(get(CorrelationIdentifier.getTraceIdKey()))
        t1VersionEnd = get(Tags.DD_VERSION)
        t1EnvEnd = get(Tags.DD_ENV)
      }
    }

    final Thread thread2 = new Thread() {
      @Override
      void run() {
        t2VersionBeg = get(Tags.DD_VERSION)
        t2EnvBeg = get(Tags.DD_ENV)
        // other trace in scope
        final AgentSpan thread2Span = startSpan("root2")
        final AgentScope thread2Scope = activateSpan(thread2Span)
        try {
          thread2TraceId.set(get(CorrelationIdentifier.getTraceIdKey()))
        } finally {
          thread2Scope.close()
          thread2Span.finish()
        }
        t2VersionEnd = get(Tags.DD_VERSION)
        t2EnvEnd = get(Tags.DD_ENV)
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
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV
    t1VersionBeg == TEST_VERSION
    t1VersionEnd == TEST_VERSION
    t2VersionBeg == TEST_VERSION
    t2VersionEnd == TEST_VERSION
    t1EnvBeg == TEST_ENV
    t1EnvEnd == TEST_ENV
    t2EnvBeg == TEST_ENV
    t2EnvEnd == TEST_ENV

    cleanup:
    mainScope?.close()
    mainSpan?.finish()
  }

  def "always log service, version, env"() {
    def mainThreadVersion = get(Tags.DD_VERSION)
    def t1threadNameBeg
    def t1threadNameEnd
    def t1VersionBeg
    def t1VersionEnd
    def t1EnvBeg
    def t1EnvEnd

    final Thread thread1 = new Thread() {
      @Override
      void run() {
        t1VersionBeg = get(Tags.DD_VERSION)
        t1EnvBeg = get(Tags.DD_ENV)
        put("threadName", currentThread().getName())

        println("something: " + this)

        t1threadNameBeg = get("threadName")
        remove("threadName")

        t1threadNameEnd = get("threadName")
        t1VersionEnd = get(Tags.DD_VERSION)
        t1EnvEnd = get(Tags.DD_ENV)

        remove(Tags.DD_VERSION)
        remove(Tags.DD_ENV)
      }
    }
    thread1.setName("thread1")
    thread1.start()
    put("threadName", Thread.currentThread().getName())
    def mainThreadNameBeg = get("threadName")
    remove("threadName")
    def mainThreadNameEnd = get("threadName")

    thread1.join()

    expect:
    mainThreadVersion == get(Tags.DD_VERSION)
    get(Tags.DD_SERVICE) != ""
    get(Tags.DD_VERSION) == TEST_VERSION
    get(Tags.DD_ENV) == TEST_ENV
    t1VersionBeg == TEST_VERSION
    t1VersionEnd == TEST_VERSION
    t1EnvBeg == TEST_ENV
    t1EnvEnd == TEST_ENV
    t1threadNameBeg == "thread1"
    mainThreadNameBeg != t1threadNameBeg
    t1threadNameEnd == null
    mainThreadNameEnd == null
  }

  def "modify thread context after clear of context map at the beginning of new thread"() {
    def t1A
    final Thread thread1 = new Thread() {
      @Override
      void run() {
        clear()
        put("a", "a thread1")
        t1A = get("a")
      }
    }
    thread1.start()
    thread1.join()

    expect:
    t1A == "a thread1"
  }

  def "test getCopyOfContextMap"() {
    final context = getMap()

    expect:
    context == getMap()
  }

}
