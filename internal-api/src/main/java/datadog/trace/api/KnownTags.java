package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.StringIndex;

/**
 * Hand-assigned tag-id constants for well-known tags, plus the {@link KnownTagCodec.Resolver} that
 * resolves them. This is the single registry shared by the tracer core and by instrumentation
 * (decorators) — it lives in {@code internal-api} so both layers can reference the ids; the
 * eventual code generator will replace the hand assignment here.
 *
 * <p>Reserved serials {@code [1, KnownTagCodec.FIRST_STORED_SERIAL)} name "virtual" tags handled by
 * the tag interceptor / span fields and are NOT stored in the {@code TagMap}; their {@code
 * fieldPos} is the {@link KnownTagCodec#NO_SLOT} sentinel that is out of slot range, so any
 * incidental store routes to the hash buckets rather than a positional slot. Serials {@code >=
 * FIRST_STORED_SERIAL} name stored tags that slot/bucket normally (or, with {@code NO_SLOT}, are
 * stored bucket-only).
 *
 * <p>The resolver registers on class initialization, so simply referencing any constant here makes
 * tag-id resolution live before the first span is built.
 *
 * <p><b>Slice-1 note (keyOf substrate):</b> the {@code fieldPos} assignments below (and {@link
 * #SLOT_COUNT}) describe a single <i>universal</i> positional layout (slots 0..25). That layout is
 * currently dormant — no dense store consumes {@code fieldPos} yet — and is <b>provisional</b>: the
 * dense-store slice replaces the universal layout with per-role / per-type sizing (see the
 * over-provision finding in {@code dense-tagmap-design.md}). {@code keyOf}/{@code nameOf} depend
 * only on {@code globalSerial} + name, not {@code fieldPos}, so the ids themselves are stable
 * across any layout scheme.
 */
public final class KnownTags {
  // slot count = (max stored fieldPos) + 1. Stored tags use fieldPos 0..25. PROVISIONAL universal
  // layout — see the slice-1 note above; the dense-store slice supersedes this with role/type
  // sizing.
  static final int SLOT_COUNT = 26;

  // ---- reserved / virtual (tag-interceptor handled, not stored) ----
  // Reserved tags are always intercepted -> set the INTERCEPTED flag.
  public static final int ERROR_SERIAL = 1;
  public static final long ERROR_ID =
      KnownTagCodec.intercepted(KnownTagCodec.tagId(ERROR_SERIAL, Tags.ERROR));

  // ---- stored (slotted / bucketed) ----
  public static final int PARENT_ID_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL;
  public static final long PARENT_ID = KnownTagCodec.tagId(PARENT_ID_SERIAL, 0, DDTags.PARENT_ID);

  // common (process-constant) tags added by InternalTagsAdder to ~every span
  public static final int BASE_SERVICE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 1;
  public static final long BASE_SERVICE_ID =
      KnownTagCodec.tagId(BASE_SERVICE_SERIAL, 1, DDTags.BASE_SERVICE);

  public static final int VERSION_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 2;
  public static final long VERSION_ID = KnownTagCodec.tagId(VERSION_SERIAL, 2, Tags.VERSION);

  // build-time-known constant tags merged into defaultSpanTags (see CoreTracer.withTracerTags).
  // "env" is a base-mixin tag; the *_ENABLED flags are product-mixin tags. Hand-assigned for now.
  public static final String ENV = "env";
  public static final int ENV_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 3;
  public static final long ENV_ID = KnownTagCodec.tagId(ENV_SERIAL, 3, ENV);

  public static final int DJM_ENABLED_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 4;
  public static final long DJM_ENABLED_ID =
      KnownTagCodec.tagId(DJM_ENABLED_SERIAL, 4, DDTags.DJM_ENABLED);

  public static final int DSM_ENABLED_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 5;
  public static final long DSM_ENABLED_ID =
      KnownTagCodec.tagId(DSM_ENABLED_SERIAL, 5, DDTags.DSM_ENABLED);

  // common tags added by the tag post-processors (RemoteHostnameAdder / IntegrationAdder /
  // ServiceNameSourceAdder). Not intercepted; stored.
  public static final int TRACER_HOST_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 6;
  public static final long TRACER_HOST_ID =
      KnownTagCodec.tagId(TRACER_HOST_SERIAL, 6, DDTags.TRACER_HOST);

  public static final int INTEGRATION_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 7;
  public static final long INTEGRATION_ID =
      KnownTagCodec.tagId(INTEGRATION_SERIAL, 7, DDTags.DD_INTEGRATION);

  public static final int SVC_SRC_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 8;
  public static final long SVC_SRC_ID = KnownTagCodec.tagId(SVC_SRC_SERIAL, 8, DDTags.DD_SVC_SRC);

  // peer.service tags, read/written by PeerServiceCalculator (post-processor; uses Map put/get that
  // bypass the interceptor). peer.service is intercepted on the set-path but STORED, so it slots.
  public static final int PEER_SERVICE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 9;
  public static final long PEER_SERVICE_ID =
      KnownTagCodec.intercepted(KnownTagCodec.tagId(PEER_SERVICE_SERIAL, 9, Tags.PEER_SERVICE));

  public static final int PEER_SERVICE_REMAPPED_FROM_SERIAL =
      KnownTagCodec.FIRST_STORED_SERIAL + 10;
  public static final long PEER_SERVICE_REMAPPED_FROM_ID =
      KnownTagCodec.tagId(PEER_SERVICE_REMAPPED_FROM_SERIAL, 10, DDTags.PEER_SERVICE_REMAPPED_FROM);

  // HTTP tags read by HttpEndpointPostProcessor. http.method/http.url are intercepted-but-stored
  // (interceptTag side-effects then returns false → stored); http.route is not intercepted. All
  // stored, so the string set-path slots them via keyOf and the id reads here find them.
  public static final int HTTP_METHOD_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 11;
  public static final long HTTP_METHOD_ID =
      KnownTagCodec.intercepted(KnownTagCodec.tagId(HTTP_METHOD_SERIAL, 11, Tags.HTTP_METHOD));

  public static final int HTTP_ROUTE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 12;
  public static final long HTTP_ROUTE_ID =
      KnownTagCodec.tagId(HTTP_ROUTE_SERIAL, 12, Tags.HTTP_ROUTE);

  public static final int HTTP_URL_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 13;
  public static final long HTTP_URL_ID =
      KnownTagCodec.intercepted(KnownTagCodec.tagId(HTTP_URL_SERIAL, 13, Tags.HTTP_URL));

  // peer connection tags set by BaseDecorator.onPeerConnection on ~every client/producer span.
  // Not intercepted; stored. Slotted (common across client instrumentations).
  public static final int PEER_HOSTNAME_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 14;
  public static final long PEER_HOSTNAME_ID =
      KnownTagCodec.tagId(PEER_HOSTNAME_SERIAL, 14, Tags.PEER_HOSTNAME);

  public static final int PEER_HOST_IPV4_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 15;
  public static final long PEER_HOST_IPV4_ID =
      KnownTagCodec.tagId(PEER_HOST_IPV4_SERIAL, 15, Tags.PEER_HOST_IPV4);

  public static final int PEER_HOST_IPV6_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 16;
  public static final long PEER_HOST_IPV6_ID =
      KnownTagCodec.tagId(PEER_HOST_IPV6_SERIAL, 16, Tags.PEER_HOST_IPV6);

  public static final int PEER_PORT_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 17;
  public static final long PEER_PORT_ID = KnownTagCodec.tagId(PEER_PORT_SERIAL, 17, Tags.PEER_PORT);

  // Universal decorator tags — set on ~every span (component/span.kind via Base/Server/Client
  // decorators, language via ServerDecorator). span.kind is intercepted (setSpanKindOrdinal).
  public static final int COMPONENT_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 18;
  public static final long COMPONENT_ID = KnownTagCodec.tagId(COMPONENT_SERIAL, 18, Tags.COMPONENT);

  public static final int SPAN_KIND_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 19;
  public static final long SPAN_KIND_ID =
      KnownTagCodec.intercepted(KnownTagCodec.tagId(SPAN_KIND_SERIAL, 19, Tags.SPAN_KIND));

  public static final int LANGUAGE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 20;
  public static final long LANGUAGE_ID =
      KnownTagCodec.tagId(LANGUAGE_SERIAL, 20, DDTags.LANGUAGE_TAG_KEY);

  // JDBC / database-client tags — set on every db span (58% of petclinic spans). Not intercepted
  // (only db.statement is, and that's handled separately).
  public static final int DB_TYPE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 21;
  public static final long DB_TYPE_ID = KnownTagCodec.tagId(DB_TYPE_SERIAL, 21, Tags.DB_TYPE);

  public static final int DB_INSTANCE_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 22;
  public static final long DB_INSTANCE_ID =
      KnownTagCodec.tagId(DB_INSTANCE_SERIAL, 22, Tags.DB_INSTANCE);

  public static final int DB_USER_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 23;
  public static final long DB_USER_ID = KnownTagCodec.tagId(DB_USER_SERIAL, 23, Tags.DB_USER);

  public static final int DB_OPERATION_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 24;
  public static final long DB_OPERATION_ID =
      KnownTagCodec.tagId(DB_OPERATION_SERIAL, 24, Tags.DB_OPERATION);

  public static final int DB_POOL_NAME_SERIAL = KnownTagCodec.FIRST_STORED_SERIAL + 25;
  public static final long DB_POOL_NAME_ID =
      KnownTagCodec.tagId(DB_POOL_NAME_SERIAL, 25, Tags.DB_POOL_NAME);

  // Open-addressed name -> id table backing keyOf (data, not a switch): scales flat as the known
  // set grows, where a generated switch eventually falls off the inline threshold. KEYOF_NAMES and
  // KEYOF_VALUES are parallel; the table places names by hash and a parallel ids[] by slot.
  private static final String[] KEYOF_NAMES = {
    Tags.ERROR,
    DDTags.PARENT_ID,
    DDTags.BASE_SERVICE,
    Tags.VERSION,
    ENV,
    DDTags.DJM_ENABLED,
    DDTags.DSM_ENABLED,
    DDTags.TRACER_HOST,
    DDTags.DD_INTEGRATION,
    DDTags.DD_SVC_SRC,
    Tags.PEER_SERVICE,
    DDTags.PEER_SERVICE_REMAPPED_FROM,
    Tags.HTTP_METHOD,
    Tags.HTTP_ROUTE,
    Tags.HTTP_URL,
    Tags.PEER_HOSTNAME,
    Tags.PEER_HOST_IPV4,
    Tags.PEER_HOST_IPV6,
    Tags.PEER_PORT,
    Tags.COMPONENT,
    Tags.SPAN_KIND,
    DDTags.LANGUAGE_TAG_KEY,
    Tags.DB_TYPE,
    Tags.DB_INSTANCE,
    Tags.DB_USER,
    Tags.DB_OPERATION,
    Tags.DB_POOL_NAME,
  };

  private static final long[] KEYOF_VALUES = {
    ERROR_ID,
    PARENT_ID,
    BASE_SERVICE_ID,
    VERSION_ID,
    ENV_ID,
    DJM_ENABLED_ID,
    DSM_ENABLED_ID,
    TRACER_HOST_ID,
    INTEGRATION_ID,
    SVC_SRC_ID,
    PEER_SERVICE_ID,
    PEER_SERVICE_REMAPPED_FROM_ID,
    HTTP_METHOD_ID,
    HTTP_ROUTE_ID,
    HTTP_URL_ID,
    PEER_HOSTNAME_ID,
    PEER_HOST_IPV4_ID,
    PEER_HOST_IPV6_ID,
    PEER_PORT_ID,
    COMPONENT_ID,
    SPAN_KIND_ID,
    LANGUAGE_ID,
    DB_TYPE_ID,
    DB_INSTANCE_ID,
    DB_USER_ID,
    DB_OPERATION_ID,
    DB_POOL_NAME_ID,
  };

  // Static-final raw arrays placed by StringIndex.EmbeddingSupport: the JIT folds these refs to
  // constants on
  // the keyOf hot path (the fastest of StringIndex's three usage modes — no instance dereference).
  private static final int[] KEYOF_HASHES;
  private static final String[] KEYOF_KEYS;
  private static final long[] KEYOF_IDS;

  static {
    StringIndex.Data data = StringIndex.EmbeddingSupport.create(KEYOF_NAMES);
    long[] ids = new long[data.names.length];
    for (int j = 0; j < KEYOF_NAMES.length; j++) {
      ids[StringIndex.EmbeddingSupport.indexOf(data.hashes, data.names, KEYOF_NAMES[j])] =
          KEYOF_VALUES[j];
    }
    KEYOF_HASHES = data.hashes;
    KEYOF_KEYS = data.names;
    KEYOF_IDS = ids;
  }

  static final KnownTagCodec.Resolver RESOLVER =
      new KnownTagCodec.Resolver() {
        @Override
        public String nameOf(long tagId) {
          switch (KnownTagCodec.globalSerial(tagId)) {
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
            case TRACER_HOST_SERIAL:
              return DDTags.TRACER_HOST;
            case INTEGRATION_SERIAL:
              return DDTags.DD_INTEGRATION;
            case SVC_SRC_SERIAL:
              return DDTags.DD_SVC_SRC;
            case PEER_SERVICE_SERIAL:
              return Tags.PEER_SERVICE;
            case PEER_SERVICE_REMAPPED_FROM_SERIAL:
              return DDTags.PEER_SERVICE_REMAPPED_FROM;
            case HTTP_METHOD_SERIAL:
              return Tags.HTTP_METHOD;
            case HTTP_ROUTE_SERIAL:
              return Tags.HTTP_ROUTE;
            case HTTP_URL_SERIAL:
              return Tags.HTTP_URL;
            case PEER_HOSTNAME_SERIAL:
              return Tags.PEER_HOSTNAME;
            case PEER_HOST_IPV4_SERIAL:
              return Tags.PEER_HOST_IPV4;
            case PEER_HOST_IPV6_SERIAL:
              return Tags.PEER_HOST_IPV6;
            case PEER_PORT_SERIAL:
              return Tags.PEER_PORT;
            case COMPONENT_SERIAL:
              return Tags.COMPONENT;
            case SPAN_KIND_SERIAL:
              return Tags.SPAN_KIND;
            case LANGUAGE_SERIAL:
              return DDTags.LANGUAGE_TAG_KEY;
            case DB_TYPE_SERIAL:
              return Tags.DB_TYPE;
            case DB_INSTANCE_SERIAL:
              return Tags.DB_INSTANCE;
            case DB_USER_SERIAL:
              return Tags.DB_USER;
            case DB_OPERATION_SERIAL:
              return Tags.DB_OPERATION;
            case DB_POOL_NAME_SERIAL:
              return Tags.DB_POOL_NAME;
            default:
              return null;
          }
        }

        @Override
        public int slotCount() {
          return SLOT_COUNT;
        }

        @Override
        public long keyOf(String name) {
          int slot = StringIndex.EmbeddingSupport.indexOf(KEYOF_HASHES, KEYOF_KEYS, name);
          return slot < 0 ? 0L : KEYOF_IDS[slot];
        }
      };

  static {
    KnownTagCodec.register(RESOLVER);
  }

  /**
   * Forces resolver registration. Merely invoking this static method runs {@code <clinit>} (which
   * registers {@link #RESOLVER}), so calling it once at tracer init flips the dense store live;
   * idempotent. Until something references this class the registry stays dormant and {@code keyOf}
   * returns 0, so tag storage is byte-identical to the bucket-only behavior.
   */
  public static void init() {}

  private KnownTags() {}
}
