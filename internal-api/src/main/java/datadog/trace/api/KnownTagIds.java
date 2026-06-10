package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;

/**
 * Hand-assigned tag-id constants for well-known tags, plus the {@link KnownTags.Resolver} that
 * resolves them. This is the single registry shared by the tracer core and by instrumentation
 * (decorators) — it lives in {@code internal-api} so both layers can reference the ids; the
 * eventual code generator will replace the hand assignment here.
 *
 * <p>Reserved serials {@code [1, KnownTags.FIRST_STORED_SERIAL)} name "virtual" tags handled by the
 * tag interceptor / span fields and are NOT stored in the {@code TagMap}; their {@code fieldPos} is
 * the {@link KnownTags#NO_SLOT} sentinel that is out of slot range, so any incidental store routes
 * to the hash buckets rather than a positional slot. Serials {@code >= FIRST_STORED_SERIAL} name
 * stored tags that slot/bucket normally (or, with {@code NO_SLOT}, are stored bucket-only).
 *
 * <p>The resolver registers on class initialization, so simply referencing any constant here makes
 * tag-id resolution live before the first span is built.
 */
public final class KnownTagIds {
  // slot count = (max stored fieldPos) + 1. Stored tags use fieldPos 0..16.
  static final int SLOT_COUNT = 17;

  // ---- reserved / virtual (tag-interceptor handled, not stored) ----
  public static final int ERROR_SERIAL = 1;
  public static final long ERROR = KnownTags.tagId(ERROR_SERIAL, Tags.ERROR);

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

  // common tags added by the tag post-processors (RemoteHostnameAdder / IntegrationAdder /
  // ServiceNameSourceAdder). Not intercepted; stored.
  public static final int TRACER_HOST_SERIAL = KnownTags.FIRST_STORED_SERIAL + 6;
  public static final long TRACER_HOST_ID =
      KnownTags.tagId(TRACER_HOST_SERIAL, 6, DDTags.TRACER_HOST);

  public static final int INTEGRATION_SERIAL = KnownTags.FIRST_STORED_SERIAL + 7;
  public static final long INTEGRATION_ID =
      KnownTags.tagId(INTEGRATION_SERIAL, 7, DDTags.DD_INTEGRATION);

  public static final int SVC_SRC_SERIAL = KnownTags.FIRST_STORED_SERIAL + 8;
  public static final long SVC_SRC_ID = KnownTags.tagId(SVC_SRC_SERIAL, 8, DDTags.DD_SVC_SRC);

  // peer.service tags, read/written by PeerServiceCalculator (post-processor; uses Map put/get that
  // bypass the interceptor). peer.service is intercepted on the set-path but STORED, so it slots.
  public static final int PEER_SERVICE_SERIAL = KnownTags.FIRST_STORED_SERIAL + 9;
  public static final long PEER_SERVICE =
      KnownTags.tagId(PEER_SERVICE_SERIAL, 9, Tags.PEER_SERVICE);

  public static final int PEER_SERVICE_REMAPPED_FROM_SERIAL = KnownTags.FIRST_STORED_SERIAL + 10;
  public static final long PEER_SERVICE_REMAPPED_FROM =
      KnownTags.tagId(PEER_SERVICE_REMAPPED_FROM_SERIAL, 10, DDTags.PEER_SERVICE_REMAPPED_FROM);

  // HTTP tags read by HttpEndpointPostProcessor. http.method/http.url are intercepted-but-stored
  // (interceptTag side-effects then returns false → stored); http.route is not intercepted. All
  // stored, so the string set-path slots them via keyOf and the id reads here find them.
  public static final int HTTP_METHOD_SERIAL = KnownTags.FIRST_STORED_SERIAL + 11;
  public static final long HTTP_METHOD = KnownTags.tagId(HTTP_METHOD_SERIAL, 11, Tags.HTTP_METHOD);

  public static final int HTTP_ROUTE_SERIAL = KnownTags.FIRST_STORED_SERIAL + 12;
  public static final long HTTP_ROUTE = KnownTags.tagId(HTTP_ROUTE_SERIAL, 12, Tags.HTTP_ROUTE);

  public static final int HTTP_URL_SERIAL = KnownTags.FIRST_STORED_SERIAL + 13;
  public static final long HTTP_URL = KnownTags.tagId(HTTP_URL_SERIAL, 13, Tags.HTTP_URL);

  // peer connection tags set by BaseDecorator.onPeerConnection on ~every client/producer span.
  // Not intercepted; stored. Slotted (common across client instrumentations).
  public static final int PEER_HOSTNAME_SERIAL = KnownTags.FIRST_STORED_SERIAL + 14;
  public static final long PEER_HOSTNAME =
      KnownTags.tagId(PEER_HOSTNAME_SERIAL, 14, Tags.PEER_HOSTNAME);

  public static final int PEER_HOST_IPV4_SERIAL = KnownTags.FIRST_STORED_SERIAL + 15;
  public static final long PEER_HOST_IPV4 =
      KnownTags.tagId(PEER_HOST_IPV4_SERIAL, 15, Tags.PEER_HOST_IPV4);

  public static final int PEER_HOST_IPV6_SERIAL = KnownTags.FIRST_STORED_SERIAL + 16;
  public static final long PEER_HOST_IPV6 =
      KnownTags.tagId(PEER_HOST_IPV6_SERIAL, 16, Tags.PEER_HOST_IPV6);

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
            case DDTags.TRACER_HOST:
              return TRACER_HOST_ID;
            case DDTags.DD_INTEGRATION:
              return INTEGRATION_ID;
            case DDTags.DD_SVC_SRC:
              return SVC_SRC_ID;
            case Tags.PEER_SERVICE:
              return PEER_SERVICE;
            case DDTags.PEER_SERVICE_REMAPPED_FROM:
              return PEER_SERVICE_REMAPPED_FROM;
            case Tags.HTTP_METHOD:
              return HTTP_METHOD;
            case Tags.HTTP_ROUTE:
              return HTTP_ROUTE;
            case Tags.HTTP_URL:
              return HTTP_URL;
            case Tags.PEER_HOSTNAME:
              return PEER_HOSTNAME;
            case Tags.PEER_HOST_IPV4:
              return PEER_HOST_IPV4;
            case Tags.PEER_HOST_IPV6:
              return PEER_HOST_IPV6;
            default:
              return 0L;
          }
        }
      };

  static {
    KnownTags.register(RESOLVER);
  }

  private KnownTagIds() {}
}
