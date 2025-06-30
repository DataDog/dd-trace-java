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
    return processBuilder
  }

  @Override
  def setup() {
    // Set the initial remote configuration before the application starts
    def initialConfig = """
        {
          "lib_config": {
            "tracing_service_mapping": [{
              "from_key": "${ServiceMappingApplication.ORIGINAL_SERVICE_NAME}",
              "to_name": "${ServiceMappingApplication.MAPPED_SERVICE_NAME}"
            }]
          }
        }
    """ as String
    setRemoteConfig("datadog/2/APM_TRACING/config_overrides/config", initialConfig)
  }

  def "Service mapping updates are observed and reported via telemetry"() {
    when:
    // Wait for the app to start and apply the initial mapping
    assert !testedProcess.waitFor(5, SECONDS) // app should still be running

    // Set the updated mapping after startup
    def updatedConfig = """
        {
          "lib_config": {
            "tracing_service_mapping": [{
              "from_key": "${ServiceMappingApplication.ORIGINAL_SERVICE_NAME}",
              "to_name": "baz"
            }]
          }
        }
    """ as String
    setRemoteConfig("datadog/2/APM_TRACING/config_overrides/config", updatedConfig)

    then:
    // Wait for the process to exit (should be 0 if both mappings observed)
    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    assert testedProcess.exitValue() == 0

    // Debug: Print all received telemetry messages
    println "=== All received telemetry messages ==="
    telemetryFlatMessages.each { msg ->
      println "Telemetry: ${msg.request_type} - ${msg.payload?.configuration?.size() ?: 0} configs"
      if (msg.payload?.configuration) {
        msg.payload.configuration.each { config ->
          if (config.name?.contains("service") || config.name?.contains("mapping")) {
            println "  Config: ${config.name} = ${config.value} (origin: ${config.origin})"
          }
        }
      }
    }
    println "=== End telemetry messages ==="

    // Check initial mapping in app-started telemetry
    def startedTelemetry = telemetryFlatMessages.find { it.request_type == "app-started" }
    assert startedTelemetry != null : "No app-started telemetry message found. Received: ${telemetryFlatMessages.collect { it.request_type }}"
    def startedConfigs = startedTelemetry.payload.configuration
    def initialMapping = startedConfigs.find {
      it.name == "service_mapping" && it.origin == "remote_config"
    }
    initialMapping != null
    initialMapping.value == "${ServiceMappingApplication.ORIGINAL_SERVICE_NAME}:${ServiceMappingApplication.MAPPED_SERVICE_NAME}"

    // Check updated mapping in app-client-configuration-change telemetry
    def changeTelemetry = telemetryFlatMessages.find { it.request_type == "app-client-configuration-change" }
    assert changeTelemetry != null : "No app-client-configuration-change telemetry message found. Received: ${telemetryFlatMessages.collect { it.request_type }}"
    def changeConfigs = changeTelemetry.payload.configuration
    def updatedMapping = changeConfigs.find {
      it.name == "service_mapping" && it.origin == "remote_config"
    }
    updatedMapping != null
    updatedMapping.value == "${ServiceMappingApplication.ORIGINAL_SERVICE_NAME}:baz"
  }
}
