package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The inject-time W3C last-parent-id ({@code p:}) is supplied as a parameter to {@link
 * PropagationTags#headerValue(PropagationTags.HeaderType, CharSequence)} rather than mutated into
 * the (possibly trace-level, shared) tags. This keeps transient per-injection identity out of
 * shared state: a sibling span's inject can't pollute another span's header, and the stored inbound
 * last-parent-id is never overwritten.
 */
class PropagationTagsLastParentIdTest {

  private static final String SPAN_A = "00000000000000aa";
  private static final String SPAN_B = "00000000000000bb";

  private static PropagationTags w3c(String header) {
    return PropagationTags.factory().fromHeaderValue(W3C, header);
  }

  @Test
  void overrideSuppliesW3cLastParentId() {
    PropagationTags tags = w3c("dd=s:1;o:rum");
    assertTrue(tags.headerValue(W3C, SPAN_A).contains("p:" + SPAN_A));
  }

  @Test
  void overrideDoesNotMutateSharedTags_noCrossTalk() {
    // One tags instance, two sibling spans injecting through it (the shared-root scenario).
    PropagationTags shared = w3c("dd=s:1;o:rum"); // no inbound p:

    String headerA = shared.headerValue(W3C, SPAN_A);
    String headerB = shared.headerValue(W3C, SPAN_B);
    String headerAagain = shared.headerValue(W3C, SPAN_A);

    assertTrue(headerA.contains("p:" + SPAN_A));
    assertTrue(headerB.contains("p:" + SPAN_B));
    // Injecting B did not change what A injects — no shared mutation.
    assertEquals(headerA, headerAagain, "a sibling inject must not change another span's header");
    // The override is never written into the shared tags (no-override header has no p:).
    assertFalse(shared.headerValue(W3C).contains("p:"), "override must not mutate the stored tags");
  }

  @Test
  void inboundLastParentIdPreservedAndUnmutatedByOverride() {
    PropagationTags tags = w3c("dd=s:1;p:" + SPAN_A); // arrived carrying a last-parent-id

    // No-override path (e.g. span-link traceState) keeps the inbound p:.
    assertTrue(tags.headerValue(W3C).contains("p:" + SPAN_A));
    // An inject override replaces it for that produced header...
    assertTrue(tags.headerValue(W3C, SPAN_B).contains("p:" + SPAN_B));
    // ...without mutating the stored inbound value.
    assertTrue(
        tags.headerValue(W3C).contains("p:" + SPAN_A), "inbound p: must survive override use");
  }
}
