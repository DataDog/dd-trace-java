package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every peer tag the Datadog Agent advertises (its /info {@code peer_tags}) resolves to a known
 * tag, directly or by span direction, so client-side stats reads them all by id rather than looking
 * each one up by name in a span's custom tags.
 */
class PeerTagsRegisteredTest {

  /** The Agent's advertised peer tags as of November 2026. */
  static final String[] AGENT_PEER_TAGS = {
    "_dd.base_service",
    "active_record.db.vendor",
    "amqp.destination",
    "amqp.exchange",
    "amqp.queue",
    "aws.queue.name",
    "aws.s3.bucket",
    "bucketname",
    "cassandra.keyspace",
    "db.cassandra.contact.points",
    "db.couchbase.seed.nodes",
    "db.hostname",
    "db.instance",
    "db.name",
    "db.namespace",
    "db.system",
    "db.type",
    "dns.hostname",
    "grpc.host",
    "hostname",
    "http.host",
    "http.server_name",
    "messaging.destination",
    "messaging.destination.name",
    "messaging.kafka.bootstrap.servers",
    "messaging.rabbitmq.exchange",
    "messaging.system",
    "mongodb.db",
    "msmq.queue.path",
    "net.peer.name",
    "network.destination.ip",
    "network.destination.name",
    "out.host",
    "peer.hostname",
    "peer.service",
    "queuename",
    "rpc.service",
    "rpc.system",
    "sequel.db.vendor",
    "server.address",
    "streamname",
    "tablename",
    "topicname"
  };

  @Test
  void everyAdvertisedPeerTagIsKnown() {
    List<String> unresolved = new ArrayList<>();
    for (String tag : AGENT_PEER_TAGS) {
      boolean known =
          KnownTagCodec.keyOf(tag) != 0
              || KnownTagCodec.directionalKeyOf(tag, KnownTagCodec.DIRECTION_INBOUND) != 0
              || KnownTagCodec.directionalKeyOf(tag, KnownTagCodec.DIRECTION_OUTBOUND) != 0;
      if (!known) {
        unresolved.add(tag);
      }
    }
    assertTrue(
        unresolved.isEmpty(),
        "peer tags the Agent advertises but the registry doesn't know: "
            + unresolved
            + " -- declare them in tag-conventions.yaml");
  }
}
