package datadog.smoketest

import datadog.smoketest.dynamicconfig.AppSecApplication
import datadog.trace.test.util.Flaky

class AppSecActivationSmokeTest extends AbstractSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    def command = [javaPath()]
    command += defaultJavaProperties.toList()
    command += [
      '-Ddd.remote_config.enabled=true',
      "-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString(),
      '-Ddd.remote_config.poll_interval.seconds=1',
      '-Ddd.profiling.enabled=false',
      '-cp',
      System.getProperty('datadog.smoketest.shadowJar.path'),
      AppSecApplication.name
    ]

    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Flaky
  void 'test activation config change is sent via RC'() {
    when:
    setRemoteConfig('datadog/2/ASM_FEATURES/asm_features_activation/config', '{"asm":{"enabled":true}}')

    then:
    waitForTelemetryFlat {
      if (it['request_type'] != 'app-client-configuration-change') {
        return false
      }
      final configurations = (List<Map<String, Object>>) it?.payload?.configuration ?: []
      final enabledConfig = configurations.find { it.name == 'appsec_enabled' }
      if (!enabledConfig) {
        return false
      }
      return enabledConfig.value == 'true' && enabledConfig .origin == 'remote_config'
    }
  }
}
