package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DDLLMObsSpanStandaloneApmScopeTest {

  private static final Field STANDALONE_APM_SCOPE_FIELD;

  private static CoreTracer tracer;

  static {
    try {
      STANDALONE_APM_SCOPE_FIELD = DDLLMObsSpan.class.getDeclaredField("standaloneApmScope");
      STANDALONE_APM_SCOPE_FIELD.setAccessible(true);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
  }

  @AfterAll
  static void closeTracer() {
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
  }

  private static DDLLMObsSpan newSpan(String kind, String name) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, "test-ml-app", null, "service", tags);
  }

  private static AgentScope standaloneApmScope(DDLLMObsSpan span) throws IllegalAccessException {
    return (AgentScope) STANDALONE_APM_SCOPE_FIELD.get(span);
  }

  @Test
  void standaloneAgentSpanActivatesApmScopeForOutgoingPropagation() throws Exception {
    // A standalone agent span (no ambient APM root) must activate its APM scope so that
    // auto-instrumented outgoing calls created within the same workflow become children of this
    // agent's APM span, keeping all LLMObs spans in one APM trace. Without this, each new
    // DDLLMObsSpan would start a fresh APM root with a different trace ID, causing the
    // trace-ID consistency gate to break parent_id and session_id inheritance.
    DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "standalone-agent");
    try {
      assertNotNull(standaloneApmScope(agentSpan));
    } finally {
      agentSpan.finish();
    }
  }

  @Test
  void nonStandaloneAgentSpanDoesNotActivateApmScope() throws Exception {
    // When an ambient APM scope already exists, the agent span is NOT the local root, so it must
    // NOT activate an additional APM scope — doing so would corrupt the active scope stack and
    // cause APM spans created after finish() to be incorrectly parented.
    AgentSpan root = AgentTracer.get().buildSpan("apm", "http.server.request").start();
    try (AgentScope rootScope = AgentTracer.activateSpan(root)) {
      DDLLMObsSpan agentSpan = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "non-standalone-agent");
      try {
        assertNull(standaloneApmScope(agentSpan));
      } finally {
        agentSpan.finish();
      }
    } finally {
      root.finish();
    }
  }
}
