package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;

/**
 * Prototype: fused tag runs with the dense store on, so setTagsNoIntercept takes its fast insertion
 * path (reserveAbsent + appendAt). The flag is read once into a static final, hence a forked JVM.
 */
class SetTagsNoInterceptDenseForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  static final CoreTracer TRACER = CoreTracer.builder().build();

  static final String[] NAMES = {"db.type", "db.user", "db.instance", "peer.hostname"};

  @Test
  void fastInsertionMatchesIndividualSetTags() {
    AgentSpan individual = TRACER.startSpan("test", "op");
    individual.setTag(KnownTags.DB_TYPE, "postgresql");
    individual.setTag(KnownTags.DB_USER, "app");
    individual.setTag(KnownTags.DB_INSTANCE, "petclinic");
    individual.setTag(KnownTags.PEER_HOSTNAME, "db.internal");
    AgentSpan fused = TRACER.startSpan("test", "op");
    fused.setTagsNoIntercept(
        KnownTags.DB_TYPE, "postgresql",
        KnownTags.DB_USER, "app",
        KnownTags.DB_INSTANCE, "petclinic",
        KnownTags.PEER_HOSTNAME, "db.internal");

    for (String name : NAMES) {
      assertEquals(individual.getTag(name), fused.getTag(name), name);
    }
  }

  @Test
  void aRunOverlappingAnExistingTagFallsBackAndOverwrites() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTag(KnownTags.DB_USER, "before");
    span.setTagsNoIntercept(
        KnownTags.DB_TYPE, "postgresql",
        KnownTags.DB_USER, "after",
        KnownTags.DB_INSTANCE, "petclinic",
        KnownTags.PEER_HOSTNAME, "db.internal");

    assertEquals("after", span.getTag("db.user"));
    assertEquals("postgresql", span.getTag("db.type"));
    assertEquals("db.internal", span.getTag("peer.hostname"));
  }

  @Test
  void runsOfThreeAndLaterSingleStoresStayConsistent() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTagsNoIntercept(
        KnownTags.PEER_HOSTNAME, "db.internal",
        KnownTags.DB_OPERATION, "SELECT",
        KnownTags.COMPONENT, "java-jdbc");
    span.setTag(KnownTags.DB_OPERATION, "UPDATE");

    assertEquals("db.internal", span.getTag("peer.hostname"));
    assertEquals("UPDATE", span.getTag("db.operation"));
  }
}
