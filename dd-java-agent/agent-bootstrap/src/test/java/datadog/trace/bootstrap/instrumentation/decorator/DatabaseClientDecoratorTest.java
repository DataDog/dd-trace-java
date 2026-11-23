package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.appsec.api.blocking.BlockingContentType.AUTO;
import static datadog.trace.api.KnownTags.AWS_RDS_ENDPOINT_TYPE_NAME;
import static datadog.trace.api.KnownTags.AWS_RDS_IDENTIFIER_NAME;
import static datadog.trace.api.KnownTags.AWS_REGION_NAME;
import static datadog.trace.api.KnownTags.DBCLUSTERIDENTIFIER_NAME;
import static datadog.trace.api.KnownTags.DBINSTANCEIDENTIFIER_NAME;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RASP_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_INSTANCE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_USER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.decorator.ExpectedSpanState.expectedSpan;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class DatabaseClientDecoratorTest extends ClientDecoratorTest {

  protected static final TracerAPI ORIGINAL_TRACER = AgentTracer.get();

  protected TraceSegment traceSegment;
  protected RequestContext reqCtx;
  protected TracerAPI tracer;

  @BeforeEach
  void registerStubTracer() {
    traceSegment = mock(TraceSegment.class, RETURNS_MOCKS);
    reqCtx = mock(RequestContext.class, RETURNS_MOCKS);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    tracer = mock(TracerAPI.class, RETURNS_MOCKS);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void restoreOriginalTracer() {
    AgentTracer.forceRegister(ORIGINAL_TRACER);
  }

  @TableTest({
    "scenario      | serviceName  ",
    "test service  | test-service ",
    "other service | other-service",
    "no service    |              "
  })
  void testDatabaseAfterStart(String serviceName) {
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator(serviceName);
    RecordingSpan recordingSpan = new RecordingSpan();

    // when
    decorator.afterStart(recordingSpan);

    // then
    ExpectedSpanState expected =
        expectedSpan()
            .spanType("test-type")
            .component("test-component")
            .spanKind("client")
            .measured(true)
            .analyticsSampleRate(1.0d);
    if (serviceName != null) {
      expected.serviceName(serviceName, "test-component");
    }
    expected.assertAppliedTo(recordingSpan);
  }

  @TableTest({
    "scenario                             | renameByInstance | instanceTypeSuffix | renameByHost | session                                           ",
    "no session                           | false            | false              | false        |                                                   ",
    "by instance, no instance             | true             | false              | false        | [user: test-user, hostname: test-hostname]        ",
    "no rename, instance and host         | false            | false              | false        | [instance: test-instance, hostname: test-hostname]",
    "by instance, instance                | true             | false              | false        | [user: test-user, instance: test-instance]        ",
    "suffix, no session                   | false            | true               | false        |                                                   ",
    "by instance with suffix, no instance | true             | true               | false        | [user: test-user, hostname: test-hostname]        ",
    "suffix only, instance and host       | false            | true               | false        | [instance: test-instance, hostname: test-hostname]",
    "by instance with suffix, instance    | true             | true               | false        | [user: test-user, instance: test-instance]        ",
    "by host, no session                  | false            | false              | true         |                                                   ",
    "by instance and host, no instance    | true             | false              | true         | [user: test-user, hostname: test-hostname]        ",
    "by host, instance and host           | false            | false              | true         | [instance: test-instance, hostname: test-hostname]",
    "by instance and host, instance       | true             | false              | true         | [user: test-user, instance: test-instance]        ",
    "suffix and host, no session          | false            | true               | true         |                                                   ",
    "all renames, no instance             | true             | true               | true         | [user: test-user, hostname: test-hostname]        ",
    "suffix and host, instance and host   | false            | true               | true         | [instance: test-instance, hostname: test-hostname]",
    "all renames, instance                | true             | true               | true         | [user: test-user, instance: test-instance]        "
  })
  void testOnConnection(
      boolean renameByInstance,
      boolean instanceTypeSuffix,
      boolean renameByHost,
      Map<String, String> session) {
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, String.valueOf(renameByInstance));
    injectSysConfig(
        DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, String.valueOf(instanceTypeSuffix));
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_HOST, String.valueOf(renameByHost));
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator();

    // when
    decorator.onConnection(span, session);

    // then
    if (session != null) {
      String instance = session.get("instance");
      String hostname = session.get("hostname");
      verify(span).setTag(DB_USER, session.get("user"));
      if (instance != null) {
        verify(span).setTag(DB_INSTANCE, instance);
      }
      if (hostname != null) {
        verify(span).setTag(PEER_HOSTNAME, (CharSequence) hostname);
      }
      if (instanceTypeSuffix && renameByInstance && instance != null) {
        verify(span).setServiceName(eq(instance + "-" + decorator.dbType()), any());
      } else if (renameByInstance && instance != null) {
        verify(span).setServiceName(eq(instance), any());
      } else if (renameByHost) {
        verify(span).setServiceName(eq(hostname), any());
      }
    }
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario       | hostname                                                        | identifier | endpointType   | instanceIdentifier | clusterIdentifier",
    "instance       | orders.c9akciq32bzq.us-east-1.rds.amazonaws.com                 | orders     | instance       | orders             |                  ",
    "cluster        | orders.cluster-c9akciq32bzq.us-east-1.rds.amazonaws.com         | orders     | cluster        |                    | orders           ",
    "cluster reader | orders.cluster-ro-c9akciq32bzq.us-east-1.rds.amazonaws.com      | orders     | cluster-ro     |                    | orders           ",
    "cluster custom | reports.cluster-custom-c9akciq32bzq.us-east-1.rds.amazonaws.com | reports    | cluster-custom |                    |                  ",
    "proxy          | orders.proxy-c9akciq32bzq.us-east-1.rds.amazonaws.com           | orders     | proxy          |                    |                  ",
    "shard group    | orders.shardgrp-c9akciq32bzq.us-east-1.rds.amazonaws.com        | orders     | shardgrp       |                    |                  ",
    "limitless      | orders.limitless-c9akciq32bzq.us-east-1.rds.amazonaws.com       | orders     | shardgrp       |                    |                  "
  })
  void testOnConnectionTagsRdsEndpointIdentity(
      String hostname,
      String identifier,
      String endpointType,
      String instanceIdentifier,
      String clusterIdentifier) {
    DatabaseClientDecorator<Map<String, String>> decorator = newRdsAwareDecorator();
    Map<String, String> session = new HashMap<>();
    session.put("user", "test-user");
    session.put("hostname", hostname);

    // when
    decorator.onConnection(span, session);

    // then
    verify(span).setTag(DB_USER, "test-user");
    verify(span).setTag(PEER_HOSTNAME, (CharSequence) hostname);
    verify(span).setTag(AWS_RDS_IDENTIFIER_NAME, identifier);
    verify(span).setTag(AWS_RDS_ENDPOINT_TYPE_NAME, endpointType);
    verify(span).setTag(AWS_REGION_NAME, "us-east-1");
    if (instanceIdentifier != null) {
      verify(span).setTag(DBINSTANCEIDENTIFIER_NAME, instanceIdentifier);
    }
    if (clusterIdentifier != null) {
      verify(span).setTag(DBCLUSTERIDENTIFIER_NAME, clusterIdentifier);
    }
    verifyNoMoreInteractions(span);
  }

  @Test
  void testOnConnectionLeavesNonRdsHostnameUntagged() {
    DatabaseClientDecorator<Map<String, String>> decorator = newRdsAwareDecorator();
    Map<String, String> session = new HashMap<>();
    session.put("user", "test-user");
    session.put("hostname", "db.internal.example.com");

    // when
    decorator.onConnection(span, session);

    // then
    verify(span).setTag(DB_USER, "test-user");
    verify(span).setTag(PEER_HOSTNAME, (CharSequence) "db.internal.example.com");
    verifyNoMoreInteractions(span);
  }

  @Test
  void testOnConnectionSkipsRdsTagsUnlessDecoratorOptsIn() {
    String hostname = "orders.c9akciq32bzq.us-east-1.rds.amazonaws.com";
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator();
    Map<String, String> session = new HashMap<>();
    session.put("user", "test-user");
    session.put("hostname", hostname);

    // when
    decorator.onConnection(span, session);

    // then
    verify(span).setTag(DB_USER, "test-user");
    verify(span).setTag(PEER_HOSTNAME, (CharSequence) hostname);
    verifyNoMoreInteractions(span);
  }

  @Test
  void testOnConnectionTagsEndpointFromLookupHook() {
    AwsRdsEndpoint cached =
        AwsRdsEndpoint.parse("orders.cluster-c9akciq32bzq.us-west-2.rds.amazonaws.com");
    Map<String, String> session = new HashMap<>();
    session.put("user", "test-user");
    session.put("hostname", "db.internal.example.com");
    AtomicReference<Map<String, String>> lookedUpConnection = new AtomicReference<>();
    AtomicReference<CharSequence> lookedUpHostname = new AtomicReference<>();
    DatabaseClientDecorator<Map<String, String>> decorator =
        new TestDatabaseClientDecorator("test-service") {
          @Override
          protected AwsRdsEndpoint rdsEndpoint(
              Map<String, String> connection, CharSequence hostname) {
            lookedUpConnection.set(connection);
            lookedUpHostname.set(hostname);
            return cached;
          }
        };

    // when
    decorator.onConnection(span, session);

    // then
    assertSame(session, lookedUpConnection.get());
    assertEquals("db.internal.example.com", lookedUpHostname.get());
    verify(span).setTag(DB_USER, "test-user");
    verify(span).setTag(PEER_HOSTNAME, (CharSequence) "db.internal.example.com");
    verify(span).setTag(AWS_RDS_IDENTIFIER_NAME, "orders");
    verify(span).setTag(AWS_RDS_ENDPOINT_TYPE_NAME, "cluster");
    verify(span).setTag(AWS_REGION_NAME, "us-west-2");
    verify(span).setTag(DBCLUSTERIDENTIFIER_NAME, "orders");
    verifyNoMoreInteractions(span);
  }

  @Test
  void testOnConnectionSkipsRdsTagsWhenLookupHookFindsNone() {
    String hostname = "orders.c9akciq32bzq.us-east-1.rds.amazonaws.com";
    Map<String, String> session = new HashMap<>();
    session.put("user", "test-user");
    session.put("hostname", hostname);
    DatabaseClientDecorator<Map<String, String>> decorator =
        new TestDatabaseClientDecorator("test-service") {
          @Override
          protected AwsRdsEndpoint rdsEndpoint(
              Map<String, String> connection, CharSequence hostname) {
            return null;
          }
        };

    // when
    decorator.onConnection(span, session);

    // then
    verify(span).setTag(DB_USER, "test-user");
    verify(span).setTag(PEER_HOSTNAME, (CharSequence) hostname);
    verifyNoMoreInteractions(span);
  }

  @TableTest({
    "scenario  | statement   ",
    "null      |             ",
    "empty     | ''          ",
    "statement | db-statement"
  })
  void testOnStatement(String statement) {
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator();

    // when
    decorator.onStatement(span, statement);

    // then
    verify(span).setResourceName(statement);
    verifyNoMoreInteractions(span);
  }

  /**
   * Fail-closed invariant: RASP must abort the SQLi attempt even when no blocking response can be
   * committed, so the throw happens whether or not a {@link BlockResponseFunction} is present.
   */
  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = APPSEC_RASP_ENABLED, value = "true")
  void testSqliBlockingThrowsEvenWithoutABlockResponseFunction() {
    RequestBlockingAction rba = new RequestBlockingAction(403, AUTO);
    stubSqlQueryCallback(rba);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);
    when(span.getRequestContext()).thenReturn(reqCtx);
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator();

    // when / then
    assertThrows(BlockingException.class, () -> decorator.onRawStatement(span, "select 1"));
  }

  @Test
  @WithConfig(key = APPSEC_ENABLED, value = "true")
  @WithConfig(key = APPSEC_RASP_ENABLED, value = "true")
  void testSqliBlockingCommitsAndThrowsWhenABlockResponseFunctionIsPresent() {
    RequestBlockingAction rba = new RequestBlockingAction(403, AUTO);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    stubSqlQueryCallback(rba);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    when(span.getRequestContext()).thenReturn(reqCtx);
    DatabaseClientDecorator<Map<String, String>> decorator = newDecorator();

    // when / then
    assertThrows(BlockingException.class, () -> decorator.onRawStatement(span, "select 1"));
    verify(brf).tryCommitBlockingResponse(reqCtx, rba);
  }

  @SuppressWarnings("unchecked")
  private void stubSqlQueryCallback(RequestBlockingAction rba) {
    CallbackProvider callbackProvider = mock(CallbackProvider.class);
    BiFunction<RequestContext, String, Flow<Void>> listener = mock(BiFunction.class);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(callbackProvider);
    when(callbackProvider.getCallback(EVENTS.databaseSqlQuery())).thenReturn(listener);
    when(listener.apply(reqCtx, "select 1")).thenReturn(blockingFlow(rba));
  }

  @Override
  protected DatabaseClientDecorator<Map<String, String>> newDecorator() {
    return newDecorator("test-service");
  }

  @Override
  protected DatabaseClientDecorator<Map<String, String>> newDecorator(String serviceName) {
    return new TestDatabaseClientDecorator(serviceName);
  }

  private static DatabaseClientDecorator<Map<String, String>> newRdsAwareDecorator() {
    return new TestDatabaseClientDecorator("test-service") {
      @Override
      protected AwsRdsEndpoint rdsEndpoint(Map<String, String> connection, CharSequence hostname) {
        return AwsRdsEndpoint.parse(hostname);
      }
    };
  }

  private static class TestDatabaseClientDecorator
      extends DatabaseClientDecorator<Map<String, String>> {
    private final String serviceName;

    TestDatabaseClientDecorator(String serviceName) {
      this.serviceName = serviceName;
    }

    @Override
    protected String[] instrumentationNames() {
      return new String[] {"test1", "test2"};
    }

    @Override
    protected String service() {
      return serviceName;
    }

    @Override
    protected CharSequence component() {
      return "test-component";
    }

    @Override
    protected CharSequence spanType() {
      return "test-type";
    }

    @Override
    protected String dbType() {
      return "test-db";
    }

    @Override
    protected String dbUser(Map<String, String> map) {
      return map.get("user");
    }

    @Override
    protected String dbInstance(Map<String, String> map) {
      return map.get("instance");
    }

    @Override
    protected String dbHostname(Map<String, String> map) {
      return map.get("hostname");
    }

    @Override
    protected boolean traceAnalyticsDefault() {
      return true;
    }
  }
}
