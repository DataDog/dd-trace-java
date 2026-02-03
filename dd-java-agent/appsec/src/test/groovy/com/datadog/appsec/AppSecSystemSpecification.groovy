package com.datadog.appsec

import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.AppSecEvent
import com.datadog.appsec.util.AbortStartupException
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.http.client.HttpClient
import datadog.metrics.api.Monitoring
import datadog.remoteconfig.ConfigurationEndListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.remoteconfig.state.ConfigKey
import datadog.remoteconfig.state.ProductListener
import datadog.trace.api.Config
import datadog.trace.api.TagMap
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

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
    'ddwaf' in AppSecSystem.startedModulesInfo
  }

  void 'throws if custom config does not exist'() {
    setup:
    injectSysConfig('dd.appsec.rules', '/file/that/does/not/exist')

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    def exception = thrown(AbortStartupException)
    exception.cause.toString().contains('/file/that/does/not/exist')
  }

  void 'system should throw AbortStartupException when config file is not valid JSON'() {
    given: 'a temporary file with invalid JSON content'
    Path path = Files.createTempFile('dd-trace-', '.json')
    path.toFile() << '{'  // Invalid JSON - missing closing brace
    injectSysConfig('dd.appsec.rules', path as String)
    rebuildConfig()

    when: 'starting the AppSec system'
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then: 'an AbortStartupException should be thrown'
    def exception = thrown(AbortStartupException)
    exception.cause instanceof IOException

    cleanup: 'delete the temporary file'
    Files.deleteIfExists(path)
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
    1 * span.getTags() >> TagMap.fromMap(['http.client_ip':'1.1.1.1'])
    1 * subService.registerCallback(EVENTS.requestEnded(), _) >> { requestEndedCB = it[1]; null }
    1 * requestContext.getData(RequestContextSlot.APPSEC) >> appSecReqCtx
    1 * requestContext.traceSegment >> traceSegment
    1 * appSecReqCtx.transferCollectedEvents() >> [Stub(AppSecEvent)]
    1 * appSecReqCtx.getRequestHeaders() >> ['foo-bar': ['1.1.1.1']]
    1 * appSecReqCtx.getResponseHeaders() >> [:]
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
    AppSecSystem.startedModulesInfo.empty
  }

  void 'updating configuration replaces the EventProducer'() {
    ProductListener savedAsmListener
    ConfigurationEndListener savedConfEndListener

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())
    EventProducerService initialEPS = AppSecSystem.REPLACEABLE_EVENT_PRODUCER.cur

    then:
    1 * poller.addListener(Product.ASM_DD, _) >> {
      savedAsmListener = it[1]
    }
    1 * poller.addConfigurationEndListener(_) >> {
      savedConfEndListener = it[0]
    }

    when:
    def config = '''
   {
     "version": "2.1",
     "rules": [
       {
         "id": "foo",
         "name": "foo",
         "conditions": [
           {
             "operator": "match_regex",
             "parameters": {
               "inputs": [
                 {
                   "address": "my.addr",
                   "key_path": ["kp"]
                 }
               ],
               "regex": "foo"
             }
           }
         ],
         "tags": {
           "type": "t",
           "category": "c"
         },
         "action": "record"
       }
     ]
   }
   '''
    savedAsmListener.accept('ignored config key' as ConfigKey, config.getBytes(), null)
    savedConfEndListener.onConfigurationEnd()

    then:
    AppSecSystem.REPLACEABLE_EVENT_PRODUCER.cur != initialEPS
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    def sco = new SharedCommunicationObjects() {
        @Override
        ConfigurationPoller configurationPoller(Config config) {
          poller
        }
      }
    sco.agentHttpClient = Stub(HttpClient)
    sco.monitoring = Mock(Monitoring)
    sco.featuresDiscovery = Stub(DDAgentFeaturesDiscovery)
    sco
  }
}
