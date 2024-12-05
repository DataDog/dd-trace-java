package datadog.trace.civisibility.domain

import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

abstract class SpanWriterTest extends DDSpecification {
  @SuppressWarnings('PropertyName')
  @Shared
  ListWriter TEST_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  AgentTracer.TracerAPI TEST_TRACER

  void setupSpec() {
    TEST_WRITER = new ListWriter()
    TEST_TRACER =
      Spy(
      CoreTracer.builder()
      .writer(TEST_WRITER)
      .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
      .build())
    TracerInstaller.forceInstallGlobalTracer(TEST_TRACER)

    TEST_TRACER.startSpan(*_) >> {
      def agentSpan = callRealMethod()
      agentSpan
    }
  }

  void cleanupSpec() {
    TEST_TRACER?.close()
  }

  void setup() {
    assert TEST_TRACER.activeSpan() == null: "Span is active before test has started: " + TEST_TRACER.activeSpan()
    TEST_TRACER.flush()
    TEST_WRITER.start()
  }

  void cleanup() {
    TEST_TRACER.flush()
  }
}
