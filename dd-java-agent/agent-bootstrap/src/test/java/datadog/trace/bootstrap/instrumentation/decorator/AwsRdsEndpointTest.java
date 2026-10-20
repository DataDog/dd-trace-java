package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.CLUSTER;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.CLUSTER_CUSTOM;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.CLUSTER_READER;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.INSTANCE;
import static datadog.trace.bootstrap.instrumentation.decorator.AwsRdsEndpoint.Type.PROXY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AwsRdsEndpointTest {

  private static final String RDS = "orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com";

  static Stream<Arguments> rdsEndpoints() {
    return Stream.of(
        Arguments.of(RDS, "orders-db", INSTANCE, "us-east-1"),
        Arguments.of(
            "Orders-DB.C9AKCIQ32BZQ.US-EAST-1.RDS.AMAZONAWS.COM",
            "orders-db",
            INSTANCE,
            "us-east-1"),
        Arguments.of(RDS + ".", "orders-db", INSTANCE, "us-east-1"),
        Arguments.of(RDS + ":5432", "orders-db", INSTANCE, "us-east-1"),
        Arguments.of(
            "orders-db.c9akciq32bzq.us-gov-west-1.rds.amazonaws.com",
            "orders-db",
            INSTANCE,
            "us-gov-west-1"),
        Arguments.of(
            "orders-db.c9akciq32bzq.eu-isoe-west-1.rds.amazonaws.com",
            "orders-db",
            INSTANCE,
            "eu-isoe-west-1"),
        Arguments.of(
            "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn",
            "orders-db",
            INSTANCE,
            "cn-north-1"),
        // China also hands out the region-before-service layout, same as the commercial partition.
        Arguments.of(
            "orders-db.c9akciq32bzq.cn-north-1.rds.amazonaws.com.cn",
            "orders-db",
            INSTANCE,
            "cn-north-1"),
        Arguments.of(
            "orders-aurora.cluster-c9akciq32bzq.cn-northwest-1.rds.amazonaws.com.cn",
            "orders-aurora",
            CLUSTER,
            "cn-northwest-1"),
        // ISO partition (aws-iso) RDS endpoints use the c2s.ic.gov DNS suffix.
        Arguments.of(
            "orders-db.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov",
            "orders-db",
            INSTANCE,
            "us-iso-east-1"),
        Arguments.of(
            "orders-aurora.cluster-ro-c9akciq32bzq.us-iso-west-1.rds.c2s.ic.gov",
            "orders-aurora",
            CLUSTER_READER,
            "us-iso-west-1"),
        // ISO-B partition (aws-iso-b) RDS endpoints use the sc2s.sgov.gov DNS suffix.
        Arguments.of(
            "orders-db.c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov",
            "orders-db",
            INSTANCE,
            "us-isob-east-1"),
        Arguments.of(
            "orders-proxy.proxy-c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov",
            "orders-proxy",
            PROXY,
            "us-isob-east-1"),
        Arguments.of(
            "orders-aurora.cluster-c9akciq32bzq.us-west-2.rds.amazonaws.com",
            "orders-aurora",
            CLUSTER,
            "us-west-2"),
        Arguments.of(
            "orders-aurora.cluster-ro-c9akciq32bzq.us-west-2.rds.amazonaws.com",
            "orders-aurora",
            CLUSTER_READER,
            "us-west-2"),
        Arguments.of(
            "reporting.cluster-custom-c9akciq32bzq.us-west-2.rds.amazonaws.com",
            "reporting",
            CLUSTER_CUSTOM,
            "us-west-2"),
        Arguments.of(
            "orders-proxy.proxy-c9akciq32bzq.ap-southeast-2.rds.amazonaws.com",
            "orders-proxy",
            PROXY,
            "ap-southeast-2"),
        Arguments.of(
            "orders-ro.endpoint.proxy-c9akciq32bzq.us-east-1.rds.amazonaws.com",
            "orders-ro",
            PROXY,
            "us-east-1"),
        // User-defined RDS Proxy endpoint in the ISO partition: the extra "endpoint" label is
        // normalized away and the c2s.ic.gov suffix is still recognised.
        Arguments.of(
            "orders-ro.endpoint.proxy-c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov",
            "orders-ro",
            PROXY,
            "us-iso-east-1"),
        Arguments.of(
            "orders-ro.endpoint.proxy-c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn",
            "orders-ro",
            PROXY,
            "cn-north-1"));
  }

  @ParameterizedTest(name = "parses {0}")
  @MethodSource("rdsEndpoints")
  void parsesRdsEndpoint(
      String hostname, String identifier, AwsRdsEndpoint.Type type, String region) {
    AwsRdsEndpoint endpoint = AwsRdsEndpoint.parse(hostname);
    assertNotNull(endpoint);
    assertEquals(identifier, endpoint.identifier());
    assertEquals(type, endpoint.type());
    assertEquals(region, endpoint.region());
  }

  @ParameterizedTest(name = "rejects {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "localhost",
        "db.internal.example.com",
        "orders-db.c9akciq32bzq.us-east-1.amazonaws.com",
        "orders-db.rds.amazonaws.com",
        "c9akciq32bzq.us-east-1.rds.amazonaws.com",
        "orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com.evil.example",
        "orders-db.not_a_hash!.us-east-1.rds.amazonaws.com",
        "orders-db.c9akciq32bzq.useast1.rds.amazonaws.com",
        "orders-db.c9akciq32bzq.us-east.rds.amazonaws.com",
        ".c9akciq32bzq.us-east-1.rds.amazonaws.com",
        "orders-db.cluster-.us-east-1.rds.amazonaws.com",
        // "endpoint" label only normalizes for proxy endpoints; a non-proxy third label is not RDS
        "orders-db.endpoint.c9akciq32bzq.us-east-1.rds.amazonaws.com",
        // an "endpoint" label whose next label is not a proxy hash is not normalized, so the ISO
        // host is left with an extra label and is not RDS
        "orders-db.endpoint.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov",
        // ISO suffix but missing the "rds" service label: not an RDS endpoint
        "orders-db.c9akciq32bzq.us-iso-east-1.c2s.ic.gov",
        // ISO suffix but a malformed Region: rejected structurally
        "orders-db.c9akciq32bzq.usiso.rds.c2s.ic.gov",
        "s3.us-east-1.amazonaws.com",
        "dynamodb.us-east-1.amazonaws.com",
      })
  void rejectsNonRdsHostname(String hostname) {
    assertNull(AwsRdsEndpoint.parse(hostname));
  }

  @Test
  void cachesParsedEndpointsPerHostname() {
    assertSame(AwsRdsEndpoint.parse(RDS), AwsRdsEndpoint.parse(RDS));
  }

  @ParameterizedTest(name = "gate accepts {0}")
  @ValueSource(
      strings = {
        RDS,
        RDS + ".",
        RDS + ":5432",
        RDS + ":5432.",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn.",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn:3306",
        "orders-db.c9akciq32bzq.cn-north-1.rds.amazonaws.com.cn",
        "orders-db.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov",
        "orders-db.c9akciq32bzq.us-iso-east-1.rds.C2S.IC.GOV",
        "orders-db.c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov",
        "ORDERS-DB.C9AKCIQ32BZQ.US-EAST-1.RDS.AMAZONAWS.COM",
        "orders-db.c9akciq32bzq.us-east-1.rds.AmazonAWS.com",
        // shaped like an AWS host, so it may reach the cache; doParse rejects it structurally
        "s3.us-east-1.amazonaws.com",
      })
  void preCacheGateAccepts(String hostname) {
    assertTrue(AwsRdsEndpoint.isPlausibleRdsHostname(hostname));
  }

  @ParameterizedTest(name = "gate rejects {0}")
  @ValueSource(
      strings = {
        "db-01.internal",
        "localhost",
        "orders-db.amazonaws.co",
        "a.b",
        "",
        // ".amazonaws.com" present but not the suffix: rejected before the cache
        "orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com.evil.example",
        "orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com.cn.evil.example",
        // ISO suffixes present but followed by further labels: rejected before the cache
        "orders-db.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov.evil.example",
        "orders-db.c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov.evil.example",
        // port must be all digits and at most five of them
        RDS + ":54x2",
        RDS + ":543210",
      })
  void preCacheGateRejects(String hostname) {
    assertFalse(AwsRdsEndpoint.isPlausibleRdsHostname(hostname));
  }

  @Test
  void nonRdsHostnamesDoNotEvictCachedRdsEndpoints() {
    AwsRdsEndpoint first = AwsRdsEndpoint.parse(RDS);
    for (int i = 0; i < 100; i++) {
      assertNull(AwsRdsEndpoint.parse("db-" + i + ".internal"));
      assertNull(AwsRdsEndpoint.parse("db-" + i + ".rds.amazonaws.com.evil.example"));
    }
    assertSame(first, AwsRdsEndpoint.parse(RDS));
  }
}
