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
  private static final Map<Object, String[]> SPECIFIC_PRECURSORS_BY_COMPONENT = new HashMap<>(6);
  private static final String[] DEFAULT_PRECURSORS = {Tags.DB_INSTANCE, Tags.PEER_HOSTNAME};

  static {
    // messaging
    SPECIFIC_PRECURSORS_BY_COMPONENT.put(
        "java-kafka", new String[] {InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS});
    // database
    SPECIFIC_PRECURSORS_BY_COMPONENT.put(
        "hazelcast-sdk", new String[] {"hazelcast.instance", Tags.PEER_HOSTNAME});
    // todo: add couchbase seed nodes when the precursor will be available
    SPECIFIC_PRECURSORS_BY_COMPONENT.put(
        "couchbase-client", new String[] {"net.peer.name", Tags.PEER_HOSTNAME});

    // fixme: cassandra instance is the keyspace and it's not adapted for the peer.service. Replace
    // with seed nodes when available
    SPECIFIC_PRECURSORS_BY_COMPONENT.put("java-cassandra", new String[] {Tags.PEER_HOSTNAME});
    // rpc
    final String[] rpcPrecursors = {Tags.RPC_SERVICE, Tags.PEER_HOSTNAME};
    SPECIFIC_PRECURSORS_BY_COMPONENT.put("grpc-client", rpcPrecursors);
    SPECIFIC_PRECURSORS_BY_COMPONENT.put("rmi-client", rpcPrecursors);
  }

  @Override
  public boolean supports() {
    return true;
  }

  private void resolve(@Nonnull final Map<String, Object> unsafeTags) {
    final Object component = unsafeTags.get(Tags.COMPONENT);
    // avoid issues with UTF8ByteString or others
    if (resolveBy(
        unsafeTags,
        SPECIFIC_PRECURSORS_BY_COMPONENT.get(component == null ? null : component.toString()))) {
      return;
    }
    // fallback to default lookup
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
    if (value != null) {
      unsafeTags.put(Tags.PEER_SERVICE, value);
      unsafeTags.put(DDTags.PEER_SERVICE_SOURCE, source);
    }
    return true;
  }

  @Nonnull
  @Override
  public Map<String, Object> tags(@Nonnull final Map<String, Object> unsafeTags) {
    // check span.kind eligibility
    final Object kind = unsafeTags.get(Tags.SPAN_KIND);
    if (Tags.SPAN_KIND_CLIENT.equals(kind) || Tags.SPAN_KIND_PRODUCER.equals(kind)) {
      // we can calculate the peer service now
      resolve(unsafeTags);
    }
    return unsafeTags;
  }
}
