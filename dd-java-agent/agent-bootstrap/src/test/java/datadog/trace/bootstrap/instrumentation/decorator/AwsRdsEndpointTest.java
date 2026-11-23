package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.config.TraceInstrumentationConfig.RDS_TAGS_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class AwsRdsEndpointTest {

  private static final String RDS = "orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com";

  @TableTest({
    "scenario                        | hostname                                                                | identifier           | type           | region         ",
    "instance                        | orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com                      | orders-db            | INSTANCE       | us-east-1      ",
    "mixed case                      | Orders-DB.C9AKCIQ32BZQ.US-EAST-1.RDS.AMAZONAWS.COM                      | orders-db            | INSTANCE       | us-east-1      ",
    "trailing dot                    | orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com.                     | orders-db            | INSTANCE       | us-east-1      ",
    "port                            | orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com:5432                 | orders-db            | INSTANCE       | us-east-1      ",
    "trailing dot and port           | orders-db.c9akciq32bzq.us-east-1.rds.amazonaws.com.:5432                | orders-db            | INSTANCE       | us-east-1      ",
    "GovCloud instance               | orders-db.c9akciq32bzq.us-gov-west-1.rds.amazonaws.com                  | orders-db            | INSTANCE       | us-gov-west-1  ",
    "ISO-E instance                  | orders-db.c9akciq32bzq.eu-isoe-west-1.rds.cloud.adc-e.uk                | orders-db            | INSTANCE       | eu-isoe-west-1 ",
    "ISO-F instance                  | orders-db.c9akciq32bzq.us-isof-east-1.rds.csp.hci.ic.gov                | orders-db            | INSTANCE       | us-isof-east-1 ",
    "ISO-F proxy                     | orders-proxy.proxy-c9akciq32bzq.us-isof-south-1.rds.csp.hci.ic.gov      | orders-proxy         | PROXY          | us-isof-south-1",
    "European sovereign instance     | orders-db.c9akciq32bzq.eusc-de-east-1.rds.amazonaws.eu                  | orders-db            | INSTANCE       | eusc-de-east-1 ",
    "China service before region     | orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn                  | orders-db            | INSTANCE       | cn-north-1     ",
    "China region before service     | orders-db.c9akciq32bzq.cn-north-1.rds.amazonaws.com.cn                  | orders-db            | INSTANCE       | cn-north-1     ",
    "China cluster                   | orders-aurora.cluster-c9akciq32bzq.cn-northwest-1.rds.amazonaws.com.cn  | orders-aurora        | CLUSTER        | cn-northwest-1 ",
    "ISO instance                    | orders-db.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov                     | orders-db            | INSTANCE       | us-iso-east-1  ",
    "ISO cluster reader              | orders-aurora.cluster-ro-c9akciq32bzq.us-iso-west-1.rds.c2s.ic.gov      | orders-aurora        | CLUSTER_READER | us-iso-west-1  ",
    "ISO-B instance                  | orders-db.c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov                 | orders-db            | INSTANCE       | us-isob-east-1 ",
    "ISO-B proxy                     | orders-proxy.proxy-c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov        | orders-proxy         | PROXY          | us-isob-east-1 ",
    "cluster                         | orders-aurora.cluster-c9akciq32bzq.us-west-2.rds.amazonaws.com          | orders-aurora        | CLUSTER        | us-west-2      ",
    "cluster reader                  | orders-aurora.cluster-ro-c9akciq32bzq.us-west-2.rds.amazonaws.com       | orders-aurora        | CLUSTER_READER | us-west-2      ",
    "custom cluster                  | reporting.cluster-custom-c9akciq32bzq.us-west-2.rds.amazonaws.com       | reporting            | CLUSTER_CUSTOM | us-west-2      ",
    "proxy                           | orders-proxy.proxy-c9akciq32bzq.ap-southeast-2.rds.amazonaws.com        | orders-proxy         | PROXY          | ap-southeast-2 ",
    "custom proxy                    | orders-ro.endpoint.proxy-c9akciq32bzq.us-east-1.rds.amazonaws.com       | orders-ro            | PROXY          | us-east-1      ",
    "ISO custom proxy                | orders-ro.endpoint.proxy-c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov      | orders-ro            | PROXY          | us-iso-east-1  ",
    "China custom proxy              | orders-ro.endpoint.proxy-c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn   | orders-ro            | PROXY          | cn-north-1     ",
    "Limitless cluster               | my-limitless-cluster.limitless-ccetp2abcdef.us-east-1.rds.amazonaws.com | my-limitless-cluster | LIMITLESS      | us-east-1      ",
    "shard group                     | orders.shardgrp-c9akciq32bzq.us-east-1.rds.amazonaws.com                | orders               | SHARD_GROUP    | us-east-1      ",
    "uppercase shard group           | ORDERS.SHARDGRP-C9AKCIQ32BZQ.US-EAST-1.RDS.AMAZONAWS.COM                | orders               | SHARD_GROUP    | us-east-1      ",
    "shard group trailing dot        | orders.shardgrp-c9akciq32bzq.us-east-1.rds.amazonaws.com.               | orders               | SHARD_GROUP    | us-east-1      ",
    "shard group port                | orders.shardgrp-c9akciq32bzq.us-east-1.rds.amazonaws.com:5432           | orders               | SHARD_GROUP    | us-east-1      ",
    "uppercase Limitless cluster     | MY-LIMITLESS-CLUSTER.LIMITLESS-CCETP2ABCDEF.US-EAST-1.RDS.AMAZONAWS.COM | my-limitless-cluster | LIMITLESS      | us-east-1      ",
    "GovCloud shard group            | orders.shardgrp-c9akciq32bzq.us-gov-west-1.rds.amazonaws.com            | orders               | SHARD_GROUP    | us-gov-west-1  ",
    "China shard group service first | orders.shardgrp-c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn            | orders               | SHARD_GROUP    | cn-north-1     ",
    "China shard group region first  | orders.shardgrp-c9akciq32bzq.cn-north-1.rds.amazonaws.com.cn            | orders               | SHARD_GROUP    | cn-north-1     "
  })
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
        "my-limitless-cluster.limitless-.us-east-1.rds.amazonaws.com",
        "my-limitless-cluster.limitless-ccet.us-east-1.rds.amazonaws.com",
        "my-limitless-cluster.limitless-ccetp2abcdef!.us-east-1.rds.amazonaws.com",
        // shard group endpoints still need a valid hash after the prefix
        "orders.shardgrp-.us-east-1.rds.amazonaws.com",
        "orders.shardgrp-c9ak.us-east-1.rds.amazonaws.com",
        "orders.shardgrp-c9akciq32bzq!.us-east-1.rds.amazonaws.com",
        "orders.shardgrp-c9akciq32bzq.us-east.rds.amazonaws.com",
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

  @TableTest({
    "scenario       | hostname                                                        | identifierTag        | identifier | endpointType  ",
    "instance       | orders.c9akciq32bzq.us-east-1.rds.amazonaws.com                 | dbinstanceidentifier | orders     | instance      ",
    "cluster        | orders.cluster-c9akciq32bzq.us-east-1.rds.amazonaws.com         | dbclusteridentifier  | orders     | cluster       ",
    "cluster reader | orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com      | dbclusteridentifier  | orders     | cluster-ro    ",
    "custom cluster | reports.cluster-custom-c9akciq32bzq.us-east-1.rds.amazonaws.com | aws.rds.identifier   | reports    | cluster-custom",
    "proxy          | orders.proxy-c9akciq32bzq.us-east-1.rds.amazonaws.com           | aws.rds.identifier   | orders     | proxy         ",
    "shard group    | orders.shardgrp-c9akciq32bzq.us-east-1.rds.amazonaws.com        | aws.rds.identifier   | orders     | shardgrp      ",
    "limitless      | orders.limitless-c9akciq32bzq.us-east-1.rds.amazonaws.com       | dbclusteridentifier  | orders     | limitless     "
  })
  void addsIdentityTagsToSpan(
      String hostname, String identifierTag, String identifier, String endpointType) {
    NameKeyedAgentSpan span = mock(NameKeyedAgentSpan.class);

    span.setTagsFrom(AwsRdsEndpoint.parse(hostname));

    verify(span).setTag(identifierTag, identifier);
    verify(span).setTag("aws.rds.endpoint_type", endpointType);
    verify(span).setTag("aws.region", "us-east-1");
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario     | hostname                                                ",
    "global       | orders.global-g2zn7e8kp7h0.global.rds.amazonaws.com     ",
    "upper case   | ORDERS.GLOBAL-G2ZN7E8KP7H0.GLOBAL.RDS.AMAZONAWS.COM     ",
    "trailing dot | orders.global-g2zn7e8kp7h0.global.rds.amazonaws.com.    ",
    "port         | orders.global-g2zn7e8kp7h0.global.rds.amazonaws.com:5432"
  })
  void parsesGlobalWriterEndpointWithoutRegion(String hostname) {
    AwsRdsEndpoint endpoint = AwsRdsEndpoint.parse(hostname);

    assertNotNull(endpoint);
    assertEquals("orders", endpoint.identifier());
    assertEquals(AwsRdsEndpoint.Type.GLOBAL_WRITER, endpoint.type());
    assertNull(endpoint.region());
  }

  @ParameterizedTest(name = "rejects global-like {0}")
  @ValueSource(
      strings = {
        "orders.global-ab.global.rds.amazonaws.com",
        "orders.g2zn7e8kp7h0.global.rds.amazonaws.com",
        ".global-g2zn7e8kp7h0.global.rds.amazonaws.com",
        "orders.global-g2zn7e8kp7h0.global.rds.amazonaws.com.cn.example"
      })
  void rejectsMalformedGlobalWriterEndpoints(String hostname) {
    assertNull(AwsRdsEndpoint.parse(hostname));
  }

  @Test
  void globalWriterEndpointOmitsRegionTag() {
    NameKeyedAgentSpan span = mock(NameKeyedAgentSpan.class);

    span.setTagsFrom(AwsRdsEndpoint.parse("orders.global-g2zn7e8kp7h0.global.rds.amazonaws.com"));

    verify(span).setTag("aws.rds.identifier", "orders");
    verify(span).setTag("aws.rds.endpoint_type", "global");
    verifyNoMoreInteractions(span);
  }

  @Test
  void addsNothingForNonRdsHostnames() {
    NameKeyedAgentSpan span = mock(NameKeyedAgentSpan.class);

    span.setTagsFrom(AwsRdsEndpoint.parse("db.internal.example.com"));

    verifyNoMoreInteractions(span);
  }

  @Test
  void cachesParsedEndpointsPerHostname() {
    assertSame(AwsRdsEndpoint.parse(RDS), AwsRdsEndpoint.parse(RDS));
  }

  @Test
  @WithConfig(key = RDS_TAGS_ENABLED, value = "false")
  void skipsParsingWhenDisabled() {
    assertNull(AwsRdsEndpoint.parse(RDS));

    CharSequence hostname = mock(CharSequence.class);
    assertNull(AwsRdsEndpoint.parse(hostname));
    verifyNoInteractions(hostname);
  }

  @ParameterizedTest(name = "gate accepts {0}")
  @ValueSource(
      strings = {
        RDS,
        RDS + ".",
        RDS + ":5432",
        RDS + ":5432.",
        RDS + ".:5432",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn.",
        "orders-db.c9akciq32bzq.rds.cn-north-1.amazonaws.com.cn:3306",
        "orders-db.c9akciq32bzq.cn-north-1.rds.amazonaws.com.cn",
        "orders-db.c9akciq32bzq.us-iso-east-1.rds.c2s.ic.gov",
        "orders-db.c9akciq32bzq.us-iso-east-1.rds.C2S.IC.GOV",
        "orders-db.c9akciq32bzq.us-isob-east-1.rds.sc2s.sgov.gov",
        "orders-db.c9akciq32bzq.eu-isoe-west-1.rds.cloud.adc-e.uk",
        "orders-db.c9akciq32bzq.us-isof-east-1.rds.csp.hci.ic.gov",
        "orders-db.c9akciq32bzq.eusc-de-east-1.rds.amazonaws.eu",
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
        "orders-db.c9akciq32bzq.eu-isoe-west-1.rds.cloud.adc-e.uk.evil.example",
        "orders-db.c9akciq32bzq.us-isof-east-1.rds.csp.hci.ic.gov.evil.example",
        "orders-db.c9akciq32bzq.eusc-de-east-1.rds.amazonaws.eu.evil.example",
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
