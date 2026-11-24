package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.instrumentation.vertx_sql_client_39.VertxSqlClientDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class VertxSqlClientDecoratorRdsEndpointTest extends AbstractInstrumentationTest {

  @TableTest({
    "scenario       | host                                                       | identifierTag        | identifier | endpointType",
    "instance       | orders.c9akciq32bzq.us-west-2.rds.amazonaws.com            | dbinstanceidentifier | orders     | instance    ",
    "cluster reader | orders.cluster-ro-c9akciq32bzq.us-west-2.rds.amazonaws.com | dbclusteridentifier  | orders     | cluster-ro  ",
    "proxy          | orders.proxy-c9akciq32bzq.us-west-2.rds.amazonaws.com      | aws.rds.identifier   | orders     | proxy       "
  })
  void tagsRdsEndpointFromDbInfo(
      String host, String identifierTag, String identifier, String endpointType) {
    Map<String, Object> tags = decorate(host);

    assertEquals(host, tags.get("peer.hostname"));
    assertEquals(identifier, tags.get(identifierTag));
    assertEquals(endpointType, tags.get("aws.rds.endpoint_type"));
    assertEquals("us-west-2", tags.get("aws.region"));
    for (String other :
        new String[] {"dbinstanceidentifier", "dbclusteridentifier", "aws.rds.identifier"}) {
      if (!other.equals(identifierTag)) {
        assertNull(tags.get(other), other);
      }
    }
  }

  @Test
  void leavesOtherHostsUntagged() {
    Map<String, Object> tags = decorate("db.example.com");

    assertEquals("db.example.com", tags.get("peer.hostname"));
    assertNull(tags.get("aws.rds.endpoint_type"));
    assertNull(tags.get("aws.region"));
  }

  private static Map<String, Object> decorate(String host) {
    DBInfo info = DBInfo.DEFAULT.toBuilder().type("postgresql").host(host).db("orders").build();
    String query = "SELECT 1";
    SingleQueryStore store =
        new SingleQueryStore(query, Pair.of(info, DBQueryInfo.ofStatement(query)));
    AgentSpan span = DECORATE.startAndDecorateSpanForStatement(query, store, false);
    try {
      return new HashMap<>(span.getTags());
    } finally {
      span.finish();
    }
  }

  /** Holds the one query the test decorates. */
  @SuppressWarnings("rawtypes")
  private static final class SingleQueryStore implements ContextStore<String, Pair> {
    private final String query;
    private final Pair info;

    SingleQueryStore(String query, Pair info) {
      this.query = query;
      this.info = info;
    }

    @Override
    public Pair get(String key) {
      return query.equals(key) ? info : null;
    }

    @Override
    public void put(String key, Pair context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Pair getOrPut(String key, Pair context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Pair getOrCompute(String key, Function<? super String, Pair> contextFactory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Pair remove(String key) {
      throw new UnsupportedOperationException();
    }
  }
}
