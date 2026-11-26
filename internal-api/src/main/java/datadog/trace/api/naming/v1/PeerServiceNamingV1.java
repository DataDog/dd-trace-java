package datadog.trace.api.naming.v1;

import static datadog.trace.api.KnownTags.COMPONENT;
import static datadog.trace.api.KnownTags.DD_PEER_SERVICE_SOURCE;
import static datadog.trace.api.KnownTags.PEER_SERVICE;
import static datadog.trace.api.KnownTags.SPAN_KIND;

import datadog.trace.api.TagStore;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.PeerServiceFormulas;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.TagReader;
import datadog.trace.bootstrap.instrumentation.api.TagWriter;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.TagsFormula;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class PeerServiceNamingV1 implements NamingSchema.ForPeerService {
  private static final Map<Object, String[]> SPECIFIC_PRECURSORS_BY_COMPONENT =
      initPrecursorsByComponent();
  private static final String[] DEFAULT_PRECURSORS = {Tags.DB_INSTANCE, Tags.PEER_HOSTNAME};

  private final Map<String, String> overridesByComponent;

  private static Map<Object, String[]> initPrecursorsByComponent() {
    final Map<Object, String[]> ret = new HashMap<>(7);
    // messaging
    ret.put("java-kafka", new String[] {InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS});
    // database
    ret.put("hazelcast-sdk", new String[] {"hazelcast.instance", Tags.PEER_HOSTNAME});
    ret.put(
        "couchbase-client",
        new String[] {
          InstrumentationTags.COUCHBASE_SEED_NODES, "net.peer.name", Tags.PEER_HOSTNAME
        });

    ret.put(
        "java-cassandra",
        new String[] {InstrumentationTags.CASSANDRA_CONTACT_POINTS, Tags.PEER_HOSTNAME});
    // rpc
    final String[] rpcPrecursors = {Tags.RPC_SERVICE, Tags.PEER_HOSTNAME};
    ret.put("grpc-client", rpcPrecursors);
    ret.put("armeria-grpc-client", rpcPrecursors);
    ret.put("rmi-client", rpcPrecursors);

    // for aws sdk we calculate eagerly to avoid doing too much complex lookups
    // this will avoid calculating defaults
    ret.put("java-aws-sdk", new String[] {});
    return ret;
  }

  public PeerServiceNamingV1(@Nonnull final Map<String, String> overridesByComponent) {
    this.overridesByComponent = overridesByComponent;
  }

  @Override
  public boolean supports() {
    return true;
  }

  private void resolve(@Nonnull final TagStore unsafeTags) {
    final Object component = unsafeTags.load(COMPONENT);
    // avoid issues with UTF8ByteString or others
    final String componentString = component == null ? null : component.toString();
    final String override = overridesByComponent.get(componentString);
    // check if value can be overridden
    if (override != null) {
      set(unsafeTags, override, "_component_override");
      return;
    }
    // otherwise try to lookup by component specific precursor
    if (resolveBy(unsafeTags, SPECIFIC_PRECURSORS_BY_COMPONENT.get(componentString))) {
      return;
    }
    // finally fallback to default lookup
    resolveBy(unsafeTags, DEFAULT_PRECURSORS);
  }

  private boolean resolveBy(
      @Nonnull final TagStore unsafeTags, @Nullable final String[] precursors) {
    if (precursors == null) {
      return false;
    }
    Object value = null;
    String source = null;
    for (String precursor : precursors) {
      value = unsafeTags.load(precursor);
      if (value != null) {
        // we have a match. Use the tag name for the source
        source = precursor;
        break;
      }
    }
    // if something matched now set the value and the source
    set(unsafeTags, value, source);
    return true;
  }

  private void set(@Nonnull final TagStore unsafeTags, Object value, String source) {
    if (value != null) {
      unsafeTags.store(PEER_SERVICE, value);
      unsafeTags.store(DD_PEER_SERVICE_SOURCE, source);
    }
  }

  @Nonnull
  @Override
  public void tags(@Nonnull final TagStore unsafeTags) {
    // check span.kind eligibility
    final Object kind = unsafeTags.load(SPAN_KIND);
    if (Tags.SPAN_KIND_CLIENT.equals(kind) || Tags.SPAN_KIND_PRODUCER.equals(kind)) {
      // we can calculate the peer service now
      resolve(unsafeTags);
    }
  }

  @Override
  public TagsFormula formula(@Nullable final String component, final Map<String, String> mapping) {
    if (component == null) {
      return (in, out) -> resolveAtRuntime(in, out, mapping);
    }
    // Resolve the component's branch of resolve() once, for spans of a known component.
    final String override = overridesByComponent.get(component);
    final String[] names =
        override != null
            ? null
            : SPECIFIC_PRECURSORS_BY_COMPONENT.getOrDefault(component, DEFAULT_PRECURSORS);
    final long[] ids = names == null ? null : PeerServiceFormulas.idsOf(names);
    return (in, out) -> {
      if (!eligible(in)) {
        return;
      }
      if (!component.equals(String.valueOf(in.get(COMPONENT)))) {
        // the span's component changed after it started: fall back to the general case
        resolveAtRuntime(in, out, mapping);
      } else if (override != null) {
        PeerServiceFormulas.write(out, override, "_component_override", mapping);
      } else {
        PeerServiceFormulas.resolveBy(in, out, ids, names, mapping);
      }
    };
  }

  private void resolveAtRuntime(TagReader in, TagWriter out, Map<String, String> mapping) {
    if (!eligible(in)) {
      return;
    }
    final Object component = in.get(COMPONENT);
    final String componentString = component == null ? null : component.toString();
    final String override = overridesByComponent.get(componentString);
    if (override != null) {
      PeerServiceFormulas.write(out, override, "_component_override", mapping);
      return;
    }
    String[] names = SPECIFIC_PRECURSORS_BY_COMPONENT.get(componentString);
    if (names == null) {
      names = DEFAULT_PRECURSORS;
    }
    PeerServiceFormulas.resolveBy(in, out, idsFor(names), names, mapping);
  }

  /** The {@code tags} span-kind check: client and producer spans only. */
  private static boolean eligible(TagReader in) {
    final Object kind = in.get(SPAN_KIND);
    return Tags.SPAN_KIND_CLIENT.equals(kind) || Tags.SPAN_KIND_PRODUCER.equals(kind);
  }

  /** The ids of every precursor list in this class's tables, resolved on first use. */
  private static final class PrecursorIds {
    static final Map<String[], long[]> BY_LIST = init();

    private static Map<String[], long[]> init() {
      Map<String[], long[]> ids = new IdentityHashMap<>();
      ids.put(DEFAULT_PRECURSORS, PeerServiceFormulas.idsOf(DEFAULT_PRECURSORS));
      for (String[] names : SPECIFIC_PRECURSORS_BY_COMPONENT.values()) {
        ids.put(names, PeerServiceFormulas.idsOf(names));
      }
      return ids;
    }
  }

  private static long[] idsFor(String[] names) {
    return PrecursorIds.BY_LIST.get(names);
  }
}
