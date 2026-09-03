package datadog.smoketest.springboot

import datadog.remoteconfig.Capabilities
import datadog.remoteconfig.Product
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.lang.Stepwise
import spock.util.concurrent.PollingConditions

/** Stable contract shared by all supported OpenFeature SDK and dd-java-agent versions. */
@Stepwise
class OpenFeatureCompatibilitySmokeTest extends AbstractOpenFeatureProviderSmokeTest {

  void 'test agent advertises feature flag remote configuration'() {
    when:
    final firstRcRequest = waitForRcClientRequest { req ->
      return true
    }

    then:
    firstRcRequest == rcClientMessages.first()
    decodeProducts(firstRcRequest).contains(Product.FFE_FLAGS)
    hasCapability(
      decodeCapabilities(firstRcRequest),
      Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES
      )
  }

  void 'test provider evaluates a flag and reports its exposure'() {
    setup:
    setRemoteConfig('datadog/2/FFE_FLAGS/1/config', rcPayload)
    final request = new Request.Builder()
    .url("http://localhost:${httpPort}/openfeature/evaluate")
    .post(RequestBody.create(MediaType.parse('application/json'), JsonOutput.toJson([
      flag: 'boolean-false-assignment',
      variationType: 'BOOLEAN',
      defaultValue: true,
      targetingKey: 'compatibility-test',
      attributes: [should_disable_feature: true]
    ])))
    .build()

    when:
    final response = client.newCall(request).execute()
    final responseBody = new JsonSlurper().parse(response.body().byteStream())

    then:
    response.code() == 200
    responseBody.value == false
    responseBody.reason == 'TARGETING_MATCH'
    responseBody.variant == 'false-variation'
    responseBody.flagMetadata.allocationKey == 'disable-feature'
    new PollingConditions(timeout: 10).eventually {
      final requests = evpProxyMessages*.getV2() as List<Map<String, Object>>
      final events = requests*.exposures.flatten()
      assert events.find {
        event ->
        event.flag.key == 'boolean-false-assignment' &&
        event.allocation.key == 'disable-feature' &&
        event.variant.key == 'false-variation' &&
        event.subject.id == 'compatibility-test'
      } != null
    }

    cleanup:
    response.close()
  }
}
