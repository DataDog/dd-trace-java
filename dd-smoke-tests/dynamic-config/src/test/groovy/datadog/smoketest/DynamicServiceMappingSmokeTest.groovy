package datadog.smoketest

import datadog.smoketest.dynamicconfig.ServiceMappingApplication

import static java.util.concurrent.TimeUnit.SECONDS

class DynamicServiceMappingSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  public static final int TIMEOUT_SECS = 30

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.remote_config.enabled=true")
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.add("-Ddd.remote_config.poll_interval.seconds=1")
    command.addAll((String[]) ["-cp", System.getProperty("datadog.smoketest.shadowJar.path")])
    command.add(ServiceMappingApplication.name)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "Updated service mapping observed"() {
    when:
    def newConfig = """
        {
          "lib_config": {
            "tracing_service_mapping": [{
              "from_key": "${ServiceMappingApplication.ORIGINAL_SERVICE_NAME}",
              "to_name": "${ServiceMappingApplication.MAPPED_SERVICE_NAME}"
            }]
          }
        }
    """ as String

    setRemoteConfig("datadog/2/APM_TRACING/config_overrides/config", newConfig)

    then:
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0
  }
}
