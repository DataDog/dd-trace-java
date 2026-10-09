package datadog.trace.core.otlp.trace;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.TagMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpSpanTagsTest {

  private static final Map<String, String> RESOURCE_TAGS = resourceTags();

  private static Map<String, String> resourceTags() {
    Map<String, String> tags = new HashMap<>();
    tags.put("team", "core");
    tags.put("region", "us1");
    return tags;
  }

  /** Mirrors the tracer-wide parent: global tags plus DD_SPAN_TAGS, env and tracer flags. */
  private static TagMap tracerTags() {
    TagMap parent = TagMap.create();
    parent.set("team", "core");
    parent.set("region", "us1");
    parent.set("owner", "alice");
    parent.set("env", "prod");
    parent.set("_dd.dsm.enabled", 1);
    return parent.freeze();
  }

  /** Collects visited tags, failing if any key is visited twice. */
  private static Map<String, Object> collect(OtlpSpanTags spanTags, TagMap tags) {
    Map<String, Object> out = new LinkedHashMap<>();
    spanTags.forEach(
        tags,
        out,
        (m, e) -> {
          Object previous = m.put(e.tag(), e.objectValue());
          assertNull(previous, "duplicate attribute " + e.tag());
        });
    return out;
  }

  @Test
  void skipsParentTagsCarriedByResource() {
    TagMap span = TagMap.createFromParent(tracerTags());
    span.set("http.method", "GET");

    Map<String, Object> expected = new HashMap<>();
    expected.put("http.method", "GET");
    expected.put("owner", "alice");
    expected.put("env", "prod");
    expected.put("_dd.dsm.enabled", 1);
    assertEquals(expected, collect(new OtlpSpanTags(RESOURCE_TAGS), span));
  }

  @Test
  void writesLocalOverridesOnce() {
    TagMap span = TagMap.createFromParent(tracerTags());
    span.set("team", "payments"); // overrides a resource-covered key
    span.set("owner", "bob"); // overrides a leftover key

    Map<String, Object> tags = collect(new OtlpSpanTags(RESOURCE_TAGS), span);
    assertEquals("payments", tags.get("team"));
    assertEquals("bob", tags.get("owner"));
    assertFalse(tags.containsKey("region"));
  }

  @Test
  void writesLeftoversCopiedFromAnotherSpanOnce() {
    TagMap parent = tracerTags();
    TagMap span = TagMap.createFromParent(parent);
    // e.g. setAllTags(otherSpan.getTags()): copies share the parent's immutable Entry instances
    span.putAll(TagMap.createFromParent(parent));

    Map<String, Object> tags = collect(new OtlpSpanTags(RESOURCE_TAGS), span);
    assertEquals("alice", tags.get("owner")); // collect() fails on duplicate keys
  }

  @Test
  void skipsLeftoversRemovedFromSpan() {
    TagMap span = TagMap.createFromParent(tracerTags());
    span.remove("owner");

    Map<String, Object> tags = collect(new OtlpSpanTags(RESOURCE_TAGS), span);
    assertFalse(tags.containsKey("owner"));
    assertEquals("prod", tags.get("env"));
  }

  @Test
  void writesEverythingWithoutParent() {
    TagMap span = TagMap.create();
    span.putAll(tracerTags());
    span.set("http.method", "GET");

    assertEquals(6, collect(new OtlpSpanTags(RESOURCE_TAGS), span).size());
  }

  @Test
  void writesEverythingWhenResourceHasNoGlobalTags() {
    TagMap span = TagMap.createFromParent(tracerTags());

    assertEquals(5, collect(new OtlpSpanTags(emptyMap()), span).size());
  }

  @Test
  void keepsTypedEntriesWhoseStringFormMatchesResource() {
    Map<String, String> resource = new HashMap<>(RESOURCE_TAGS);
    resource.put("_dd.dsm.enabled", "1");
    TagMap span = TagMap.createFromParent(tracerTags());

    // resource only has the string "1", so the numeric entry is still written to keep its type
    assertEquals(1, collect(new OtlpSpanTags(resource), span).get("_dd.dsm.enabled"));
  }

  @Test
  void recomputesLeftoversWhenParentChanges() {
    OtlpSpanTags spanTags = new OtlpSpanTags(RESOURCE_TAGS);
    assertFalse(collect(spanTags, TagMap.createFromParent(tracerTags())).containsKey("team"));

    // e.g. remote config replaced the tracer tags: the static resource still says team=core
    TagMap updated = TagMap.create();
    updated.set("team", "payments");
    updated.set("region", "us1");
    TagMap span = TagMap.createFromParent(updated.freeze());

    Map<String, Object> tags = collect(spanTags, span);
    assertEquals("payments", tags.get("team"));
    assertFalse(tags.containsKey("region"));
  }
}
