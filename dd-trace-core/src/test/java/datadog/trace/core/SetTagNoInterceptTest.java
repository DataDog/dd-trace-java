package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;

/**
 * setTagNoIntercept is the target of a build-time rewrite for ids classified as never intercepted,
 * but it must stay correct for any id: misclassification may cost speed, never behavior.
 */
class SetTagNoInterceptTest {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  @Test
  void storesANonInterceptedTag() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTagNoIntercept(KnownTags.HTTP_ROUTE, "/owners/{id}");

    assertEquals("/owners/{id}", span.getTag("http.route"));
  }

  @Test
  void stillInterceptsAnInterceptedTag() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTagNoIntercept(KnownTags.SERVICE, "renamed-service");

    // service is intercepted: it renames the span rather than being stored as a plain tag
    assertEquals("renamed-service", span.getServiceName());
  }

  @Test
  void emptyStringRemovesLikeSetTag() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTagNoIntercept(KnownTags.HTTP_ROUTE, "/r");
    span.setTagNoIntercept(KnownTags.HTTP_ROUTE, "");

    assertNull(span.getTag("http.route"));
  }

  @Test
  void fusedRunMatchesIndividualSetTags() {
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

    for (String name : new String[] {"db.type", "db.user", "db.instance", "peer.hostname"}) {
      assertEquals(individual.getTag(name), fused.getTag(name), name);
    }
  }

  @Test
  void fusedRunWithAnInterceptedIdStillIntercepts() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTagsNoIntercept(
        KnownTags.HTTP_ROUTE, "/r",
        KnownTags.SERVICE, "renamed-service",
        KnownTags.DB_USER, "app");

    assertEquals("renamed-service", span.getServiceName());
    assertEquals("/r", span.getTag("http.route"));
    assertEquals("app", span.getTag("db.user"));
  }

  @Test
  void fusedRunWithAnEmptyValueRemovesThatTag() {
    AgentSpan span = TRACER.startSpan("test", "op");
    span.setTag(KnownTags.DB_USER, "app");
    span.setTagsNoIntercept(
        KnownTags.HTTP_ROUTE, "/r",
        KnownTags.DB_USER, "",
        KnownTags.DB_INSTANCE, "petclinic");

    assertNull(span.getTag("db.user"));
    assertEquals("/r", span.getTag("http.route"));
    assertEquals("petclinic", span.getTag("db.instance"));
  }

  @Test
  void charSequenceVariantMatchesSetTag() {
    AgentSpan viaSetTag = TRACER.startSpan("test", "op");
    AgentSpan viaNoIntercept = TRACER.startSpan("test", "op");
    CharSequence value = new StringBuilder("db.internal");
    viaSetTag.setTag(KnownTags.PEER_HOSTNAME, value);
    viaNoIntercept.setTagNoIntercept(KnownTags.PEER_HOSTNAME, value);

    assertEquals(
        String.valueOf(viaSetTag.getTag("peer.hostname")),
        String.valueOf(viaNoIntercept.getTag("peer.hostname")));
  }
}
