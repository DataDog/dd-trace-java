package datadog.trace.core.otlp.trace;

import datadog.trace.api.TagMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Visits the span tags to export over OTLP, leaving out tags the trace resource already carries.
 *
 * <p>Tracer-wide tags (global tags, {@code DD_SPAN_TAGS}, {@code env}, ...) are shared by spans as
 * the frozen read-through parent of each span's {@link TagMap}. Global tags are also exported once
 * per payload as resource attributes, so repeating them on every span is redundant. The parent's
 * other "leftover" entries are worked out once per parent and cached; each span then writes its own
 * local tags followed by whichever leftovers it can still see.
 *
 * <p>Spans without a read-through parent (e.g. when tracer-wide tags need interception and are
 * copied into each span) have all their tags written, as before.
 *
 * <p>Not thread-safe: each encoder owns its own instance.
 */
final class OtlpSpanTags {
  private static final TagMap.EntryReader[] NO_ENTRIES = new TagMap.EntryReader[0];

  /** Attributes carried by the trace resource. */
  private final Map<String, String> resourceAttributes;

  /** Read-through parent the cached leftovers were computed from. */
  private TagMap cachedParent;

  /** Entries of {@link #cachedParent} not covered by the resource, so still written per span. */
  private TagMap.EntryReader[] cachedLeftovers = NO_ENTRIES;

  OtlpSpanTags(Map<String, String> resourceAttributes) {
    this.resourceAttributes = resourceAttributes;
  }

  <T> void forEach(TagMap tags, T thisObj, BiConsumer<T, ? super TagMap.EntryReader> consumer) {
    TagMap parent = tags.parent();
    if (null == parent || resourceAttributes.isEmpty()) {
      // nothing shared with the resource
      tags.forEach(thisObj, consumer);
      return;
    }
    tags.forEachLocal(thisObj, consumer);
    for (TagMap.EntryReader leftover : leftovers(parent)) {
      // identity check: skips leftovers the span overrode locally (already written) or removed
      if (tags.getEntry(leftover.tag()) == leftover) {
        consumer.accept(thisObj, leftover);
      }
    }
  }

  private TagMap.EntryReader[] leftovers(TagMap parent) {
    if (parent != cachedParent) {
      List<TagMap.EntryReader> leftovers = new ArrayList<>();
      parent.forEach(
          entry -> {
            if (!coveredByResource(entry)) {
              leftovers.add(entry);
            }
          });
      cachedLeftovers = leftovers.toArray(NO_ENTRIES);
      cachedParent = parent;
    }
    return cachedLeftovers;
  }

  /**
   * Covered when the resource has the same key and value. Only object entries qualify, since those
   * are written as string attributes just like the resource; numeric and boolean entries keep their
   * type on the span.
   */
  private boolean coveredByResource(TagMap.EntryReader entry) {
    return entry.isObject() && entry.stringValue().equals(resourceAttributes.get(entry.tag()));
  }
}
