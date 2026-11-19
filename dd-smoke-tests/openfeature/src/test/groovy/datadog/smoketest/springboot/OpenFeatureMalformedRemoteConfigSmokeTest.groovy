package datadog.smoketest.springboot

import datadog.smoketest.AbstractServerSmokeTest
import okhttp3.Request
import okhttp3.RequestBody

/**
 * A malformed flag configuration that Remote Configuration delivers before the SDK subscribes is
 * acknowledged by the agent, then reported as not applied once the SDK rejects it.
 */
class OpenFeatureMalformedRemoteConfigSmokeTest extends AbstractServerSmokeTest {
  private static final String CONFIG_PATH = 'datadog/2/FFE_FLAGS/1/config'
  private static final String MALFORMED_CONFIG = '{"flags":[]}'
  private static final int APPLY_STATE_ACKNOWLEDGED = 2
  private static final int APPLY_STATE_ERROR = 3

  @Override
  ProcessBuilder createProcessBuilder() {
    setRemoteConfig(CONFIG_PATH, MALFORMED_CONFIG)

    final springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")
    final command = [javaPath()]
    command.addAll(defaultJavaProperties)
    command.add('-Ddd.remote_config.enabled=true')
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.addAll([
      '-jar',
      springBootShadowJar,
      "--server.port=${httpPort}".toString(),
      '--openfeature.lazy-provider=true'
    ])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE', 'remote_config')
    return builder
  }

  /** The provider never gets a valid configuration, so the OpenFeature SDK logs its failed initialization. */
  @Override
  boolean isErrorLog(final String line) {
    return super.isErrorLog(line) && !line.contains(' d.o.sdk.') && !line.contains(' dev.openfeature.sdk.')
  }

  void 'test malformed configuration received before the SDK is reported as not applied'() {
    setup:
    // The base resets the Remote Configuration response before each test.
    setRemoteConfig(CONFIG_PATH, MALFORMED_CONFIG)
    final served = rcClientMessages.size()

    when: 'the agent receives the configuration while no SDK has subscribed'
    final acknowledged = waitForRequestAfter(served) { applyState(it) == APPLY_STATE_ACKNOWLEDGED }
    final response = client.newCall(new Request.Builder()
      .url("http://localhost:${httpPort}/openfeature/provider")
      .post(RequestBody.create(null, new byte[0]))
      .build()).execute()

    then: 'the SDK rejects it and the agent reports it as not applied'
    response.code() == 202
    final rejected = waitForRequestAfter(acknowledged) { applyState(it) == APPLY_STATE_ERROR }
    (configState(rejected).apply_error as String).contains('Malformed flag configuration')

    cleanup:
    response?.close()
  }

  /**
   * Waits for an agent Remote Configuration request matching the predicate.
   *
   * @param from the index of the first request to consider.
   * @param predicate the predicate to match.
   * @return the index of the first matching request.
   */
  private int waitForRequestAfter(final int from, final Closure<Boolean> predicate) {
    int index = -1
    defaultPoll.eventually {
      index = rcClientMessages.findIndexOf(from, predicate)
      assert index >= 0
    }
    return index
  }

  private Map<String, Object> configState(final int index) {
    final request = rcClientMessages[index]
    final states = request.client?.state?.config_states as List<Map<String, Object>>
    return states?.find {
      it.product == 'FFE_FLAGS'
    }
  }

  private static Integer applyState(final Map<String, Object> request) {
    final states = request.client?.state?.config_states as List<Map<String, Object>>
    return states?.find {
      it.product == 'FFE_FLAGS'
    }?.apply_state as Integer
  }
}
