package datadog.trace.core;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;

/**
 * Hand-assigned tag-id constants for tracer-core tags, plus the {@link KnownTags.Resolver} that
 * resolves them.
 *
 * <p>Reserved serials {@code [1, KnownTags.FIRST_STORED_SERIAL)} name "virtual" tags handled by the
 * tag interceptor / span fields and are NOT stored in the {@code TagMap}; their {@code fieldPos} is
 * a sentinel ({@link #RESERVED_FIELD_POS}) that is out of slot range, so any incidental store
 * routes to the hash buckets rather than a positional slot. Serials {@code >= FIRST_STORED_SERIAL}
 * name stored tags that slot/bucket normally.
 *
 * <p>The resolver registers on class initialization, so simply referencing any constant here makes
 * tag-id resolution live before the first span is built.
 */
public final class CoreTagIds {
  // sentinel fieldPos for reserved (non-stored) tags: >= TagMap KNOWN_ENTRIES_CAPACITY, so set()
  // can never place them in a positional slot
  static final int RESERVED_FIELD_POS = 0xFFFF;

  // ---- reserved / virtual (tag-interceptor handled, not stored) ----
  public static final int ERROR_SERIAL = 1;
  public static final long ERROR = KnownTags.tagId(ERROR_SERIAL, RESERVED_FIELD_POS, Tags.ERROR);

  // ---- stored (slotted / bucketed) ----
  public static final int PARENT_ID_SERIAL = KnownTags.FIRST_STORED_SERIAL;
  public static final long PARENT_ID = KnownTags.tagId(PARENT_ID_SERIAL, 0, DDTags.PARENT_ID);

  static final KnownTags.Resolver RESOLVER =
      new KnownTags.Resolver() {
        @Override
        public String nameOf(long tagId) {
          switch (KnownTags.globalSerial(tagId)) {
            case ERROR_SERIAL:
              return Tags.ERROR;
            case PARENT_ID_SERIAL:
              return DDTags.PARENT_ID;
            default:
              return null;
          }
        }

        @Override
        public long keyOf(String name) {
          switch (name) {
            case Tags.ERROR:
              return ERROR;
            case DDTags.PARENT_ID:
              return PARENT_ID;
            default:
              return 0L;
          }
        }
      };

  static {
    KnownTags.register(RESOLVER);
  }

  private CoreTagIds() {}
}
