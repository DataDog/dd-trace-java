package datadog.trace.bootstrap.instrumentation.jdbc;

import static datadog.trace.api.config.TraceInstrumentationConfig.RDS_TAGS_ENABLED;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.CLUSTER;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.INSTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class DBInfoTest {

  private static final String CLUSTER_HOST =
      "orders.cluster-c9akciq32bzq.us-east-1.rds.amazonaws.com";
  private static final String INSTANCE_HOST = "orders-1.c9akciq32bzq.us-east-1.rds.amazonaws.com";

  @Test
  void resolvesRdsEndpointFromHostWhenBuilt() {
    DBInfo info = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();

    AwsRdsEndpoint endpoint = info.getRdsEndpoint();
    assertNotNull(endpoint);
    assertEquals("orders", endpoint.identifier());
    assertEquals(CLUSTER, endpoint.type());
    assertEquals("us-east-1", endpoint.region());
    // resolved once at construction, so every read returns the same instance
    assertSame(endpoint, info.getRdsEndpoint());
  }

  @Test
  void hasNoRdsEndpointForOtherHosts() {
    assertNull(
        new DBInfo.Builder().type("postgresql").host("db.example.com").build().getRdsEndpoint());
    assertNull(new DBInfo.Builder().type("postgresql").build().getRdsEndpoint());
    assertNull(DBInfo.DEFAULT.getRdsEndpoint());
  }

  @Test
  @WithConfig(key = RDS_TAGS_ENABLED, value = "false")
  void hasNoRdsEndpointWhenTaggingIsDisabled() {
    DBInfo info = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();

    assertNull(info.getRdsEndpoint());
    assertEquals(CLUSTER_HOST, info.getHost());
  }

  @Test
  void toBuilderCopyKeepsRdsEndpoint() {
    DBInfo info = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();

    DBInfo copy = info.toBuilder().user("app").build();

    assertSame(info.getRdsEndpoint(), copy.getRdsEndpoint());
  }

  @Test
  void toBuilderHostChangeResolvesNewHost() {
    DBInfo info = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();

    DBInfo instance = info.toBuilder().host(INSTANCE_HOST).build();
    DBInfo other = info.toBuilder().host("db.example.com").build();

    assertNotNull(instance.getRdsEndpoint());
    assertEquals("orders-1", instance.getRdsEndpoint().identifier());
    assertEquals(INSTANCE, instance.getRdsEndpoint().type());
    assertNull(other.getRdsEndpoint());
  }

  @Test
  void rdsEndpointDoesNotChangeEquality() {
    DBInfo first = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();
    DBInfo second = new DBInfo.Builder().type("postgresql").host(CLUSTER_HOST).build();

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }
}
