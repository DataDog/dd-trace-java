package datadog.trace.llmobs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The agent manifest is carried inside the metadata tag under the reserved {@code _dd} namespace,
 * so metadata annotations and manifest annotations must not destroy each other in either order.
 */
class DDLLMObsSpanMetadataTest {

  private static final String METADATA_TAG = "_ml_obs_tag.metadata";
  private static final Field SPAN_FIELD;

  private static CoreTracer tracer;

  static {
    try {
      SPAN_FIELD = DDLLMObsSpan.class.getDeclaredField("span");
      SPAN_FIELD.setAccessible(true);
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

  private static DDLLMObsSpan newAgentSpan(String name) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(
        Tags.LLMOBS_AGENT_SPAN_KIND, name, "test-ml-app", null, "service", tags);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> metadata(DDLLMObsSpan span) throws IllegalAccessException {
    return (Map<String, Object>) ((AgentSpan) SPAN_FIELD.get(span)).getTag(METADATA_TAG);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> reservedDd(DDLLMObsSpan span) throws IllegalAccessException {
    return (Map<String, Object>) metadata(span).get("_dd");
  }

  private static LLMObs.AgentManifest manifest(String name) {
    return LLMObs.AgentManifest.builder().name(name).instructions("Do things.").build();
  }

  private static Map<String, Object> callerDd(String key, Object value) {
    return Collections.singletonMap("_dd", Collections.singletonMap(key, value));
  }

  @Test
  void manifestSurvivesMetadataAnnotationCarryingItsOwnReservedNamespace() throws Exception {
    DDLLMObsSpan span = newAgentSpan("agent");
    span.annotateAgentManifest(manifest("agent"));
    span.setMetadata(callerDd("cost_tags", Collections.singletonList("model:gpt-4o")));

    Map<String, Object> dd = reservedDd(span);
    assertTrue(dd.containsKey("agent_manifest"));
    assertEquals(Collections.singletonList("model:gpt-4o"), dd.get("cost_tags"));
  }

  @Test
  void callerSuppliedReservedNamespaceIsKeptWhenThereIsNoManifest() throws Exception {
    DDLLMObsSpan span = newAgentSpan("agent");
    span.setMetadata(Collections.singletonMap("tenant", "acme"));
    span.setMetadata(callerDd("cost_tags", Collections.singletonList("a")));

    assertEquals(Collections.singletonList("a"), reservedDd(span).get("cost_tags"));
  }
}
