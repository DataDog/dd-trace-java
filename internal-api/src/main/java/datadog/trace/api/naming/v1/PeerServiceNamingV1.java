package datadog.trace.api.naming.v1;

import datadog.trace.api.DDTags;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
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

  private void resolve(@Nonnull final Map<String, Object> unsafeTags) {
    final Object component = unsafeTags.get(Tags.COMPONENT);
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
      @Nonnull final Map<String, Object> unsafeTags, @Nullable final String[] precursors) {
    if (precursors == null) {
      return false;
    }
    Object value = null;
    String source = null;
    for (String precursor : precursors) {
      value = unsafeTags.get(precursor);
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

  private void set(@Nonnull final Map<String, Object> unsafeTags, Object value, String source) {
    if (value != null) {
      unsafeTags.put(Tags.PEER_SERVICE, value);
      unsafeTags.put(DDTags.PEER_SERVICE_SOURCE, source);
    }
  }

  @Override
  public void tags(@Nonnull final Map<String, Object> unsafeTags) {
    // check span.kind eligibility
    final Object kind = unsafeTags.get(Tags.SPAN_KIND);
    if (Tags.SPAN_KIND_CLIENT.equals(kind) || Tags.SPAN_KIND_PRODUCER.equals(kind)) {
      // we can calculate the peer service now
      resolve(unsafeTags);
    }
  }
}
