package datadog.smoketest.appsec

import groovy.json.JsonSlurper
import spock.lang.Shared

/**
 * Smoke test for SCA Reachability (DD_APPSEC_SCA_ENABLED=true).
 *
 * Verifies that the tracer reports vulnerable library classes via the
 * app-dependencies-loaded telemetry heartbeat using the RFC stateful model:
 *
 *   1. At startup, vulnerable dependencies are reported with metadata: [{cve, reached:[]}]
 *      (signals the backend that SCA is monitoring those CVEs).
 *   2. Once a class from the vulnerable library is loaded, reached is populated
 *      with the first callsite.
 *
 * The springboot smoke test app uses jackson-databind:2.6.0, which falls in the
 * vulnerable range "< 2.6.7.3" for GHSA-645p-88qh-w398. Spring Boot auto-configures
 * Jackson at startup so com.fasterxml.jackson.databind.ObjectMapper is always loaded.
 */
class ScaReachabilitySmokeTest extends AbstractAppSecServerSmokeTest {

  @Shared
  String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    // Enable SCA Reachability
    command.add("-Ddd.appsec.sca.enabled=true")
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  void 'SCA reachability reports vulnerable jackson-databind via telemetry'() {
    when: 'application starts and telemetry heartbeats arrive'
    waitForTelemetryFlat { it.get('request_type') == 'app-dependencies-loaded' }

    then: 'jackson-databind 2.6.0 appears with SCA reachability metadata'
    // Collect all dependencies from all app-dependencies-loaded messages
    def allDependencies = []
    telemetryFlatMessages.findAll { it.get('request_type') == 'app-dependencies-loaded' }.each {
      def payload = it.get('payload') as Map
      def deps = payload?.get('dependencies') as List
      if (deps) allDependencies.addAll(deps)
    }

    // Find the jackson-databind entry that has SCA reachability metadata.
    // The same dependency may appear multiple times: once from the regular dependency
    // detector (no metadata) and once from the SCA periodic action (with metadata).
    // We must search for the entry that actually carries reachability metadata.
    def jacksonDep = allDependencies.find { dep ->
      def d = dep as Map
      d.get('name') == 'com.fasterxml.jackson.core:jackson-databind' &&
        (d.get('metadata') as List)?.any { (it as Map).get('type') == 'reachability' }
    } as Map

    assert jacksonDep != null :
    "jackson-databind must appear with SCA reachability metadata in app-dependencies-loaded"
    assert jacksonDep.get('version') == '2.6.0' : "must be the vulnerable version 2.6.0"

    // Find the reachability metadata entry
    def metadata = jacksonDep.get('metadata') as List
    def reachabilityEntry = metadata.find { entry ->
      (entry as Map).get('type') == 'reachability'
    } as Map

    assert reachabilityEntry != null : "at least one reachability metadata entry expected"

    // Parse the stringified JSON value
    def valueJson = reachabilityEntry.get('value') as String
    assert valueJson != null && !valueJson.isEmpty() : "value must not be empty"

    def reachabilityPayload = new JsonSlurper().parseText(valueJson) as Map
    assert reachabilityPayload.get('id') != null : "CVE id must be present"
    assert reachabilityPayload.get('id').toString().startsWith('GHSA-') :
    "id must be a GHSA identifier, got: ${reachabilityPayload.get('id')}"
    assert reachabilityPayload.get('reached') instanceof List : "reached must be a list"

    // ObjectMapper is always loaded by Spring Boot — reached must be non-empty
    def reached = reachabilityPayload.get('reached') as List
    assert !reached.isEmpty() :
    "ObjectMapper is loaded at Spring Boot startup — reached must contain at least one callsite"

    def callsite = reached[0] as Map
    assert callsite.get('path') != null : "callsite path must be present"
    assert callsite.get('symbol') != null : "callsite symbol must be present"
    assert (callsite.get('line') as int) >= 0 : "callsite line must be non-negative"
  }
}
