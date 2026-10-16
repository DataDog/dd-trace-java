package datadog.trace.api;

import datadog.trace.api.TagMap.Entry;

/**
 * Where a tag name is resolved and an operation is routed to the store that holds it: a known tag
 * to the {@link IdStore} by its id, a custom tag to the name store by its name. The one place a
 * TagMap turns a name into an id, so the stores themselves never see a name that might be a known
 * tag's.
 *
 * <p>A name resolves once, by {@link #keyOf}, to a key: a known tag's id ({@code > 0}), 0 for a
 * custom tag, or a negative sentinel for a direction-scoped OpenTelemetry alias ({@code
 * server.address}), whose tag depends on the span's direction. An alias is the caller's to decide:
 * the routing methods treat any key that is not an id as custom, which is what a TagMap -- it has
 * no direction -- does with an alias; a caller that knows the direction resolves the alias to its
 * tag first.
 *
 * <p>Static, so a span can route to its stores the same way a TagMap does.
 */
final class TagRouting {
  private TagRouting() {}

  /** {@code tag}'s key: its id when known, 0 when custom, negative for a direction-scoped alias. */
  static long keyOf(String tag) {
    return KnownTagCodec.keyOrAliasOf(tag);
  }

  /** Whether {@code key} is a known tag's id, as opposed to a custom tag or an alias. */
  static boolean isKnown(long key) {
    return key > 0;
  }

  /** The entry for {@code tag}, already resolved to {@code key}, reading through to parents. */
  static Entry get(IdStore ids, HashStore names, long key, String tag) {
    return isKnown(key) ? ids.get(key) : names.get(tag);
  }

  /** Removes {@code tag}, already resolved to {@code key}, returning the prior visible entry. */
  static Entry remove(IdStore ids, HashStore names, long key, String tag) {
    return isKnown(key) ? ids.remove(key) : names.remove(tag);
  }

  /** Puts {@code entry} into the store for its kind, returning the local entry it replaced. */
  static Entry put(IdStore ids, HashStore names, Entry entry) {
    return entry.tagId() != 0 ? ids.put(entry) : names.put(entry);
  }

  /** {@link #put}, returning the prior visible entry. */
  static Entry getAndPut(IdStore ids, HashStore names, Entry entry) {
    return entry.tagId() != 0 ? ids.getAndPut(entry) : names.getAndPut(entry);
  }
}
