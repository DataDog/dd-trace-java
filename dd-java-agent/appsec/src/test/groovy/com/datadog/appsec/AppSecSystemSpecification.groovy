package com.datadog.appsec

import com.datadog.appsec.util.AbortStartupException
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.communication.monitor.Monitoring
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.test.util.DDSpecification
import okhttp3.OkHttpClient

import java.nio.file.Files
import java.nio.file.Path

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
    System.setProperty('dd.appsec.rules', '/file/that/does/not/exist')
    rebuildConfig()

    when:
    AppSecSystem.start(subService, sharedCommunicationObjects())

    then:
    thrown AbortStartupException
  }

  void 'throws if the config file is not parseable'() {
    setup:
    Path path = Files.createTempFile('dd-trace-', '.json')
    path.toFile() << '{'
    System.setProperty('dd.appsec.rules', path as String)
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
