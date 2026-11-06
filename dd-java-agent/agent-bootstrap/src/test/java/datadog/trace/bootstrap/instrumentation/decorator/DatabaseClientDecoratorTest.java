package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.appsec.api.blocking.BlockingContentType.AUTO;
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
import java.util.Map;
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
    return new DatabaseClientDecorator<Map<String, String>>() {
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
    };
  }
}
