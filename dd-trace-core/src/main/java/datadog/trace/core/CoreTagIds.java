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

  // common (process-constant) tags added by InternalTagsAdder to ~every span
  public static final int BASE_SERVICE_SERIAL = KnownTags.FIRST_STORED_SERIAL + 1;
  public static final long BASE_SERVICE =
      KnownTags.tagId(BASE_SERVICE_SERIAL, 1, DDTags.BASE_SERVICE);

  public static final int VERSION_SERIAL = KnownTags.FIRST_STORED_SERIAL + 2;
  public static final long VERSION = KnownTags.tagId(VERSION_SERIAL, 2, Tags.VERSION);

  // build-time-known constant tags merged into defaultSpanTags (see CoreTracer.withTracerTags).
  // "env" is a base-mixin tag; the *_ENABLED flags are product-mixin tags. Hand-assigned for now.
  public static final String ENV = "env";
  public static final int ENV_SERIAL = KnownTags.FIRST_STORED_SERIAL + 3;
  public static final long ENV_ID = KnownTags.tagId(ENV_SERIAL, 3, ENV);

  public static final int DJM_ENABLED_SERIAL = KnownTags.FIRST_STORED_SERIAL + 4;
  public static final long DJM_ENABLED = KnownTags.tagId(DJM_ENABLED_SERIAL, 4, DDTags.DJM_ENABLED);

  public static final int DSM_ENABLED_SERIAL = KnownTags.FIRST_STORED_SERIAL + 5;
  public static final long DSM_ENABLED = KnownTags.tagId(DSM_ENABLED_SERIAL, 5, DDTags.DSM_ENABLED);

  static final KnownTags.Resolver RESOLVER =
      new KnownTags.Resolver() {
        @Override
        public String nameOf(long tagId) {
          switch (KnownTags.globalSerial(tagId)) {
            case ERROR_SERIAL:
              return Tags.ERROR;
            case PARENT_ID_SERIAL:
              return DDTags.PARENT_ID;
            case BASE_SERVICE_SERIAL:
              return DDTags.BASE_SERVICE;
            case VERSION_SERIAL:
              return Tags.VERSION;
            case ENV_SERIAL:
              return ENV;
            case DJM_ENABLED_SERIAL:
              return DDTags.DJM_ENABLED;
            case DSM_ENABLED_SERIAL:
              return DDTags.DSM_ENABLED;
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
            case DDTags.BASE_SERVICE:
              return BASE_SERVICE;
            case Tags.VERSION:
              return VERSION;
            case ENV:
              return ENV_ID;
            case DDTags.DJM_ENABLED:
              return DJM_ENABLED;
            case DDTags.DSM_ENABLED:
              return DSM_ENABLED;
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
