package datadog.trace.bootstrap.instrumentation.decorator

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.CallbackProvider
import static datadog.trace.api.gateway.Events.EVENTS
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX
import static datadog.trace.bootstrap.instrumentation.decorator.ExpectedSpanState.expectedSpan

class DatabaseClientDecoratorTest extends ClientDecoratorTest {

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  protected traceSegment
  protected reqCtx
  protected tracer

  void setup() {
    traceSegment = Stub(TraceSegment)
    reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
    }
    tracer = Stub(AgentTracer.TracerAPI)
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  def span = mockSpan()

  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)
    def recordingSpan = new RecordingSpan()

    when:
    decorator.afterStart(recordingSpan)

    then:
    def expected = expectedSpan()
      .spanType("test-type")
      .component("test-component")
      .spanKind("client")
      .measured(true)
      .analyticsSampleRate(1.0d)
    if (serviceName != null) {
      expected.serviceName(serviceName, "test-component")
    }
    expected.assertAppliedTo(recordingSpan)

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test onConnection"() {
    setup:
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameByInstance")
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "$instanceTypeSuffix")
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_HOST, "$renameByHost")
    def decorator = newDecorator()

    when:
    decorator.onConnection(span, session)

    then:
    if (session) {
      1 * span.setTag(Tags.DB_USER, session.user)
      if (session.instance != null) {
        1 * span.setTag(Tags.DB_INSTANCE, session.instance)
      }
      if (session.hostname != null) {
        1 * span.setTag(Tags.PEER_HOSTNAME, session.hostname)
      }
      if (instanceTypeSuffix && renameByInstance && session.instance) {
        1 * span.setServiceName(session.instance + "-" + decorator.dbType(), _)
      } else if (renameByInstance && session.instance) {
        1 * span.setServiceName(session.instance, _)
      } else if (renameByHost) {
        1 * span.setServiceName(session.hostname, _)
      }
    }
    0 * _

    where:
    renameByInstance | instanceTypeSuffix | renameByHost  | session
    false            | false              | false         | null
    true             | false              | false         | [user: "test-user", hostname: "test-hostname"]
    false            | false              | false         | [instance: "test-instance", hostname: "test-hostname"]
    true             | false              | false         | [user: "test-user", instance: "test-instance"]
    false            | true               | false         | null
    true             | true               | false         | [user: "test-user", hostname: "test-hostname"]
    false            | true               | false         | [instance: "test-instance", hostname: "test-hostname"]
    true             | true               | false         | [user: "test-user", instance: "test-instance"]
    false            | false              | true          | null
    true             | false              | true          | [user: "test-user", hostname: "test-hostname"]
    false            | false              | true          | [instance: "test-instance", hostname: "test-hostname"]
    true             | false              | true          | [user: "test-user", instance: "test-instance"]
    false            | true               | true          | null
    true             | true               | true          | [user: "test-user", hostname: "test-hostname"]
    false            | true               | true          | [instance: "test-instance", hostname: "test-hostname"]
    true             | true               | true          | [user: "test-user", instance: "test-instance"]
  }

  def "test onStatement"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onStatement(span, statement)

    then:
    1 * span.setResourceName(statement)
    0 * _

    where:
    statement      | _
    null           | _
    ""             | _
    "db-statement" | _
  }

  /**
   * Fail-closed invariant: RASP must abort the SQLi attempt even when no blocking response can be
   * committed, so the throw happens whether or not a {@link BlockResponseFunction} is present.
   */
  void "test SQLi blocking throws even without a BlockResponseFunction"() {
    setup:
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
    def rba = new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    callbackProvider.getCallback(EVENTS.databaseSqlQuery()) >> listener
    listener.apply(reqCtx, "select 1") >> new Flow<Void>() {
        @Override
        Flow.Action getAction() {
          return rba
        }

        @Override
        Void getResult() {
          return null
        }
      }
    reqCtx.getBlockResponseFunction() >> null
    span.getRequestContext() >> reqCtx

    def decorator = newDecorator()

    when:
    decorator.onRawStatement(span, "select 1")

    then:
    thrown(BlockingException)
  }

  void "test SQLi blocking commits and throws when a BlockResponseFunction is present"() {
    setup:
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
    def rba = new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    final brf = Mock(BlockResponseFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    callbackProvider.getCallback(EVENTS.databaseSqlQuery()) >> listener
    listener.apply(reqCtx, "select 1") >> new Flow<Void>() {
        @Override
        Flow.Action getAction() {
          return rba
        }

        @Override
        Void getResult() {
          return null
        }
      }
    reqCtx.getBlockResponseFunction() >> brf
    span.getRequestContext() >> reqCtx

    def decorator = newDecorator()

    when:
    decorator.onRawStatement(span, "select 1")

    then:
    thrown(BlockingException)
    1 * brf.tryCommitBlockingResponse(reqCtx, rba)
  }

  @Override
  def newDecorator(String serviceName = "test-service") {
    return new DatabaseClientDecorator<Map>() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected String service() {
          return serviceName
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        @Override
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected String dbType() {
          return "test-db"
        }

        @Override
        protected String dbUser(Map map) {
          return map.user
        }

        @Override
        protected String dbInstance(Map map) {
          return map.instance
        }

        @Override
        protected String dbHostname(Map map) {
          return map.hostname
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      }
  }
}
