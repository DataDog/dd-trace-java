package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.CLUSTER_READER;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import org.junit.jupiter.api.Test;

class JDBCDecoratorRdsEndpointTest {

  @Test
  void usesEndpointCachedOnDbInfo() {
    DBInfo info =
        JDBCConnectionUrlParser.extractDBInfo(
            "jdbc:postgresql://orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com:5432/orders",
            null);

    AwsRdsEndpoint endpoint = DECORATE.rdsEndpoint(info, info.getHost());

    assertNotNull(endpoint);
    assertSame(info.getRdsEndpoint(), endpoint);
    assertEquals("orders", endpoint.identifier());
    assertEquals(CLUSTER_READER, endpoint.type());
    assertEquals("us-east-1", endpoint.region());
  }

  @Test
  void hasNoEndpointForOtherHosts() {
    DBInfo info =
        JDBCConnectionUrlParser.extractDBInfo("jdbc:postgresql://db.example.com:5432/orders", null);

    assertNull(DECORATE.rdsEndpoint(info, info.getHost()));
  }
}
