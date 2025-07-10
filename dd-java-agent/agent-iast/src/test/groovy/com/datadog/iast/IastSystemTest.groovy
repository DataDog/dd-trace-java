package com.datadog.iast


import datadog.trace.api.config.IastConfig
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.api.iast.sink.HstsMissingHeaderModule
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.InsecureCookieModule
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule
import datadog.trace.api.iast.sink.NoSameSiteCookieModule
import datadog.trace.api.iast.sink.StacktraceLeakModule
import datadog.trace.api.iast.sink.XContentTypeModule
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.Agent
import datadog.trace.test.logging.TestLogCollector
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.test.TaintedObjectsUtils.noOpTaintedObjects
import static datadog.trace.api.iast.IastContext.Mode.GLOBAL
import static datadog.trace.api.iast.IastContext.Mode.REQUEST

class IastSystemTest extends DDSpecification {

  def setup() {
    InstrumentationBridge.clearIastModules()
  }

  def cleanup() {
    TestLogCollector.disable()
  }

  void 'start'() {
    given:
    TestLogCollector.enable()
    final ig = new InstrumentationGateway()
    final ss = Spy(ig.getSubscriptionService(RequestContextSlot.IAST))
    final cbp = ig.getCallbackProvider(RequestContextSlot.IAST)
    final traceSegment = Mock(TraceSegment)
    final to = noOpTaintedObjects()
    final iastContext = Spy(new IastRequestContext(to))
    final RequestContext reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
      getData(RequestContextSlot.IAST) >> iastContext
    }
    final igSpanInfo = Mock(IGSpanInfo)

    when:
    IastSystem.start(ss)

    then:
    1 * ss.registerCallback(Events.get().requestStarted(), _)
    1 * ss.registerCallback(Events.get().requestEnded(), _)
    1 * ss.registerCallback(Events.get().requestHeader(), _)
    1 * ss.registerCallback(Events.get().httpRoute(), _)
    1 * ss.registerCallback(Events.get().grpcServerRequestMessage(), _)
    0 * _
    TestLogCollector.drainCapturedLogs().any { it.message.contains('IAST is starting') }

    when:
    final startCallback = cbp.getCallback(Events.get().requestStarted())
    final endCallback = cbp.getCallback(Events.get().requestEnded())

    then:
    startCallback != null
    endCallback != null
    0 * _

    when:
    startCallback.get()
    endCallback.apply(reqCtx, igSpanInfo)

    then:
    1 * iastContext.getTaintedObjects()
    1 * iastContext.setTaintedObjects(_)
    1 * iastContext.getMetricCollector()
    1 * traceSegment.setTagTop('_dd.iast.enabled', 1)
    1 * iastContext.getxContentTypeOptions() >> 'nosniff'
    1 * iastContext.getStrictTransportSecurity() >> 'max-age=35660'
    1 * iastContext.getAuthorization()
    0 * _
    noExceptionThrown()
  }

  void 'start disabled'() {
    setup:
    injectSysConfig('dd.iast.enabled', "false")
    rebuildConfig()
    final ss = Mock(SubscriptionService)

    when:
    IastSystem.start(ss)

    then:
    0 * _
  }

  void 'start context mode'() {
    setup:
    injectSysConfig(IastConfig.IAST_CONTEXT_MODE, mode.name())
    rebuildConfig()
    final ig = new InstrumentationGateway()
    final ss = Spy(ig.getSubscriptionService(RequestContextSlot.IAST))

    when:
    IastSystem.start(ss)

    then:
    final provider = IastContext.Provider.INSTANCE
    assert providerClass.isInstance(provider)

    where:
    mode    | providerClass
    GLOBAL  | IastGlobalContext.Provider
    REQUEST | IastRequestContext.Provider
  }

  @SuppressWarnings('GroovyAccessibility')
  void 'test opt out modules'() {
    setup:
    ClassLoader defaultAgentClassLoader = Agent.AGENT_CLASSLOADER
    boolean defaultAppSecEnabled = Agent.appSecEnabled
    boolean defaultIastEnabled = Agent.iastEnabled
    boolean defaultIastFullyDisabled = Agent.iastFullyDisabled

    and:
    Agent.AGENT_CLASSLOADER = Thread.currentThread().contextClassLoader
    setSysConfig('iast.enabled', iast)
    setSysConfig('appsec.enabled', appsec)
    Agent.appSecEnabled = Agent.isFeatureEnabled(Agent.AgentFeature.APPSEC)
    Agent.iastEnabled = Agent.isFeatureEnabled(Agent.AgentFeature.IAST)
    Agent.iastFullyDisabled = Agent.isIastFullyDisabled(Agent.appSecEnabled)

    and:
    InstrumentationBridge.clearIastModules()

    when:
    Agent.maybeStartIast(null)

    then:
    InstrumentationBridge.iastModules.each {
      final module = InstrumentationBridge.getIastModule(it)
      if (Agent.iastEnabled) {
        assert module != null: "All modules should be started if IAST is enabled"
      } else if (optOut) {
        assert (module != null) == shouldBeOptOut(it): "Only opt out modules should be enabled"
      } else {
        assert module == null: "No modules should be started if IAST is disabled"
      }
    }

    cleanup:
    Agent.AGENT_CLASSLOADER = defaultAgentClassLoader
    Agent.appSecEnabled = defaultAppSecEnabled
    Agent.iastEnabled = defaultIastEnabled
    Agent.iastFullyDisabled = defaultIastFullyDisabled

    where:
    iast  | appsec | optOut
    null  | null   | false
    null  | true   | true
    null  | false  | false
    true  | null   | false
    true  | true   | false
    true  | false  | false
    false | null   | false
    false | true   | false
    false | false  | false
  }

  private void setSysConfig(String property, Boolean value) {
    if (value != null) {
      injectSysConfig(property, value.toString())
    } else {
      removeSysConfig(property)
    }
  }

  private static final List<Class<? extends IastModule>> OPT_OUT_MODULES = [
    HttpResponseHeaderModule,
    InsecureCookieModule,
    NoHttpOnlyCookieModule,
    NoSameSiteCookieModule,
    HstsMissingHeaderModule,
    XContentTypeModule,
    ApplicationModule,
    StacktraceLeakModule
  ]

  private static boolean shouldBeOptOut(final Class<?> target) {
    return OPT_OUT_MODULES.find { it.isAssignableFrom(target) } != null
  }
}
