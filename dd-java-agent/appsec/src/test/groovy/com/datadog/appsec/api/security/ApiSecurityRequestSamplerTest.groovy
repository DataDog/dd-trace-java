package com.datadog.appsec.api.security

import com.datadog.appsec.config.AppSecFeatures
import com.datadog.appsec.config.AppSecFeaturesDeserializer
import datadog.remoteconfig.ConfigurationChangesTypedListener
import datadog.remoteconfig.ConfigurationPoller
import datadog.remoteconfig.Product
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class ApiSecurityRequestSamplerTest extends DDSpecification {

  @Shared
  static final float DEFAULT_SAMPLE_RATE = Config.get().getApiSecurityRequestSampleRate()

  void 'Api Security Request Sample Rate'() {
    given:
    def config = Spy(Config.get())
    config.getApiSecurityRequestSampleRate() >> sampleRate
    def sampler = new ApiSecurityRequestSampler(config)

    when:
    def numOfRequest = expectedSampledRequests.size()
    def results = new int[numOfRequest]
    for (int i = 0; i < numOfRequest; i++) {
      results[i] = sampler.sampleRequest() ? 1 : 0
    }

    then:
    results == expectedSampledRequests as int[]

    where:
    sampleRate               | expectedSampledRequests
    DEFAULT_SAMPLE_RATE      | [0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0]  // Default sample rate - 10%
    0.0                      | [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
    0.1                      | [0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0]
    0.25                     | [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1]
    0.33                     | [0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1]
    0.5                      | [0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1]
    0.75                     | [0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1]
    0.9                      | [0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0]
    0.99                     | [0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
    1.0                      | [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
    1.25                     | [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]    // Wrong sample rate - use 100%
    -0.5                     | [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]    // Wrong sample rate - use 100%
  }

  void 'update sample rate via remote-config'() {
    given:
    ConfigurationPoller poller = Mock()
    def config = Spy(Config.get())
    ConfigurationChangesTypedListener<AppSecFeatures> listener
    AppSecFeatures newConfig = new AppSecFeatures().tap {
      asm = new AppSecFeatures.Asm().tap {
        enabled = true
      }
      apiSecurity = new AppSecFeatures.ApiSecurity().tap {
        requestSampleRate = 0.2
      }
    }

    when:
    def sampler = new ApiSecurityRequestSampler(config, poller)

    then:
    1 * poller.addListener(Product.ASM_FEATURES, 'asm_api_security', AppSecFeaturesDeserializer.INSTANCE, _) >> {
      listener = it[3] as ConfigurationChangesTypedListener<AppSecFeatures>
    }
    listener != null

    when:
    listener.accept(null, newConfig, null)

    then:
    sampler.sampling == 20
  }
}
