package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionContext;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class JDBCDecoratorRdsEndpointTest extends AbstractInstrumentationTest {

  @TableTest({
    "scenario       | url                                                                                  | identifierTag        | identifier | endpointType   | region   ",
    "instance       | jdbc:postgresql://orders.c9akciq32bzq.us-west-2.rds.amazonaws.com:5432/orders        | dbinstanceidentifier | orders     | instance       | us-west-2",
    "cluster        | jdbc:mysql://orders.cluster-c9akciq32bzq.us-west-2.rds.amazonaws.com:3306/orders     | dbclusteridentifier  | orders     | cluster        | us-west-2",
    "cluster reader | jdbc:postgresql://orders.cluster-ro-c9akciq32bzq.us-west-2.rds.amazonaws.com/orders  | dbclusteridentifier  | orders     | cluster-ro     | us-west-2",
    "custom cluster | jdbc:postgresql://reports.cluster-custom-c9akciq32bzq.us-west-2.rds.amazonaws.com/db | aws.rds.identifier   | reports    | cluster-custom | us-west-2",
    "proxy          | jdbc:postgresql://orders.proxy-c9akciq32bzq.us-west-2.rds.amazonaws.com/orders       | aws.rds.identifier   | orders     | proxy          | us-west-2"
  })
  void tagsRdsEndpointFromDbInfo(
      String url, String identifierTag, String identifier, String endpointType, String region) {
    Map<String, Object> tags = decorate(url);

    assertEquals(identifier, tags.get(identifierTag));
    assertEquals(endpointType, tags.get("aws.rds.endpoint_type"));
    assertEquals(region, tags.get("aws.region"));
    // The identifier is written under exactly one key.
    for (String other :
        new String[] {"dbinstanceidentifier", "dbclusteridentifier", "aws.rds.identifier"}) {
      if (!other.equals(identifierTag)) {
        assertNull(tags.get(other), other);
      }
    }
  }

  @Test
  void leavesOtherHostsUntagged() {
    Map<String, Object> tags = decorate("jdbc:postgresql://db.example.com:5432/orders");

    assertEquals("db.example.com", tags.get("peer.hostname"));
    assertNull(tags.get("aws.rds.endpoint_type"));
    assertNull(tags.get("aws.region"));
  }

  private static Map<String, Object> decorate(String url) {
    DBInfo info = JDBCConnectionUrlParser.extractDBInfo(url, null);
    AgentSpan span = startSpan("test", "jdbc.query");
    try {
      DECORATE.onConnection(span, new JDBCConnectionContext(info));
    } finally {
      span.finish();
    }
    return new HashMap<>(span.getTags());
  }
}
