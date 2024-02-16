package com.datadog.appsec

import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.AppSecEvent
import com.datadog.appsec.util.AbortStartupException
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Counter
import datadog.communication.monitor.Monitoring
import datadog.remoteconfig.ConfigurationChangesTypedListener
import datadog.remoteconfig.ConfigurationEndListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification
import okhttp3.OkHttpClient

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class AppSecSystemSpecification extends DDSpecification {
  SubscriptionService subService = Mock()
  ConfigurationPoller poller = Mock()

  def cleanup() {
    AppSecSystem.stop()
  }

  void 'registers powerwaf module'() {
    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    'powerwaf' in AppSecSystem.startedModulesInfo
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
    IGSpanInfo span = Mock(AgentSpan)

    setup:
    injectSysConfig('dd.appsec.ipheader', 'foo-bar')

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())
    requestEndedCB.apply(requestContext, span)

    then:
    1 * span.getTags() >> ['http.client_ip':'1.1.1.1']
    1 * subService.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    1 * requestContext.getData(RequestContextSlot.APPSEC) >> appSecReqCtx
    1 * requestContext.traceSegment >> traceSegment
    1 * appSecReqCtx.transferCollectedEvents() >> [Stub(AppSecEvent)]
    1 * appSecReqCtx.getRequestHeaders() >> ['foo-bar': ['1.1.1.1']]
    1 * appSecReqCtx.getResponseHeaders() >> [:]
    1 * traceSegment.setTagTop('actor.ip', '1.1.1.1')
  }

  void 'honors appsec.trace.rate.limit'() {
    BiFunction<RequestContext, AgentSpan, Flow<Void>> requestEndedCB
    RequestContext requestContext = Mock()
    TraceSegment traceSegment = Mock()
    AppSecRequestContext appSecReqCtx = Mock()
    def sco = sharedCommunicationObjects()
    Counter throttledCounter = Mock()
    IGSpanInfo span = Mock(AgentSpan)

    setup:
    injectSysConfig('dd.appsec.trace.rate.limit', '5')

    when:
    AppSecSystem.start(subService, sco)
    7.times { requestEndedCB.apply(requestContext, span) }

    then:
    span.getTags() >> ['http.client_ip':'1.1.1.1']
    1 * sco.monitoring.newCounter('_dd.java.appsec.rate_limit.dropped_traces') >> throttledCounter
    1 * subService.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    7 * requestContext.getData(RequestContextSlot.APPSEC) >> appSecReqCtx
    7 * requestContext.traceSegment >> traceSegment
    7 * appSecReqCtx.transferCollectedEvents() >> [Stub(AppSecEvent)]
    // allow for one extra in case we move to another second and round down the prev count
    (5..6) * appSecReqCtx.getRequestHeaders() >> [:]
    (5..6) * appSecReqCtx.getResponseHeaders() >> [:]
    (5..6) * traceSegment.setDataTop("appsec", _)
    (1..2) * throttledCounter.increment(1)
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
    AppSecSystem.startedModulesInfo.empty
  }

  void 'updating configuration replaces the EventProducer'() {
    ConfigurationChangesTypedListener<AppSecConfig> savedAsmListener
    ConfigurationEndListener savedConfEndListener

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())
    EventProducerService initialEPS = AppSecSystem.REPLACEABLE_EVENT_PRODUCER.cur

    then:
    1 * poller.addListener(Product.ASM_DD, _, _) >> {
      savedAsmListener = it[2]
    }
    1 * poller.addConfigurationEndListener(_) >> {
      savedConfEndListener = it[0]
    }

    when:
    savedAsmListener.accept('ignored config key',
      AppSecConfig.valueOf([version: '2.1', rules: [
          [
            id: 'foo',
            name: 'foo',
            conditions: [
              [
                operator: 'match_regex',
                parameters: [
                  inputs: [
                    [
                      address: 'my.addr',
                      key_path: ['kp'],
                    ]
                  ],
                  regex: 'foo',
                ]
              ]
            ],
            tags: [
              type: 't',
              'category': 'c',
            ],
            action: 'record',
          ]
        ]]), null)
    savedConfEndListener.onConfigurationEnd()

    then:
    AppSecSystem.REPLACEABLE_EVENT_PRODUCER.cur != initialEPS
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    def sco = new SharedCommunicationObjects(
      ) {
        @Override
        ConfigurationPoller configurationPoller(Config config) {
          poller
        }
      }
    sco.okHttpClient = Stub(OkHttpClient)
    sco.monitoring = Mock(Monitoring)
    sco.featuresDiscovery = Stub(DDAgentFeaturesDiscovery)
    sco
  }
}
