package com.datadog.appsec

import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.util.AbortStartupException
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.api.TraceSegment
import datadog.trace.api.function.BiFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import okhttp3.OkHttpClient

import java.nio.file.Files
import java.nio.file.Path

import static datadog.trace.api.gateway.Events.EVENTS

class AppSecSystemSpecification extends DDSpecification {
  SubscriptionService subService = Mock()

  def cleanup() {
    AppSecSystem.stop()
  }

  void 'registers powerwaf module'() {
    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    'powerwaf' in AppSecSystem.startedModuleNames
  }

  void 'throws if custom config does not exist'() {
    setup:
    injectSysConfig('dd.appsec.rules', '/file/that/does/not/exist')

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    thrown AbortStartupException
  }

  void 'honors appsec.ipheader'() {
    // unfortunately the way to test this is honored requires us to get into the weeds
    BiFunction<RequestContext, AgentSpan, Flow<Void>> requestEndedCB
    RequestContext requestContext = Mock()
    TraceSegment traceSegment = Mock()
    AppSecRequestContext appSecReqCtx = Mock()

    setup:
    injectSysConfig('dd.appsec.ipheader', 'foo-bar')

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())
    requestEndedCB.apply(requestContext, Mock(AgentSpan))

    then:
    1 * subService.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    1 * requestContext.data >> appSecReqCtx
    1 * requestContext.traceSegment >> traceSegment
    1 * appSecReqCtx.transferCollectedEvents() >> [Mock(AppSecEvent100)]
    1 * appSecReqCtx.getRequestHeaders() >> ['foo-bar': ['1.1.1.1']]
    1 * traceSegment.setTagTop('actor.ip', '1.1.1.1')
  }

  void 'throws if the config file is not parseable'() {
    setup:
    Path path = Files.createTempFile('dd-trace-', '.json')
    path.toFile() << '{'
    injectSysConfig('dd.appsec.rules', path as String)
    rebuildConfig()

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    thrown AbortStartupException
  }

  void 'when not started returns empty list for started modules'() {
    expect:
    AppSecSystem.startedModuleNames.empty
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    new SharedCommunicationObjects(
      okHttpClient: Mock(OkHttpClient),
      monitoring: Mock(Monitoring),
      featuresDiscovery: Mock(DDAgentFeaturesDiscovery)
      )
  }
}
