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
 *
 * The springboot smoke test app includes junrar:7.5.5 (vulnerable under GHSA-hf5p-q87m-crj7,
 * range >=0,<7.5.10). ScaReachabilityInit forces loading of LocalFolderExtractor at startup via
 * Class.forName. The transformer enqueues it, retransforms on the first heartbeat, registers the
 * CVE with reached:[], and injects callbacks for createDirectory and createFile. Neither method
 * is called at startup, so reached stays empty.
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

  void 'SCA reachability reports vulnerable junrar via telemetry'() {
    when: 'application starts and telemetry heartbeats arrive'
    waitForTelemetryFlat({ event ->
      if (event.get('request_type') != 'app-dependencies-loaded') {
        return false
      }
      def deps = event.get('payload')?.get('dependencies') as List
      deps?.any { dep ->
        def d = dep as Map
        d.get('name') == 'com.github.junrar:junrar' &&
          (d.get('metadata') as List)?.any { (it as Map).get('type') == 'reachability' }
      }
    })

    then: 'junrar 7.5.5 appears with SCA reachability metadata'
    // Collect all dependencies from all app-dependencies-loaded messages
    def allDependencies = []
    telemetryFlatMessages.findAll { it.get('request_type') == 'app-dependencies-loaded' }.each {
      def payload = it.get('payload') as Map
      def deps = payload?.get('dependencies') as List
      if (deps) {
        allDependencies.addAll(deps)
      }
    }

    // Find the junrar entry that has SCA reachability metadata.
    // ScaReachabilityInit loads LocalFolderExtractor at startup; the transformer retransforms it
    // on the first heartbeat and registers the CVE with reached:[]. Neither createDirectory nor
    // createFile is called, so reached stays empty.

    def junrarDep = allDependencies.find { dep ->
      def d = dep as Map
      d.get('name') == 'com.github.junrar:junrar' &&
        (d.get('metadata') as List)?.any { (it as Map).get('type') == 'reachability' }
    } as Map

    assert junrarDep != null :
    "junrar must appear with SCA reachability metadata in app-dependencies-loaded"
    assert junrarDep.get('version') == '7.5.5' : "must be the vulnerable version 7.5.5"

    // Find the reachability metadata entry
    def metadata = junrarDep.get('metadata') as List
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

    // junrar has method-level symbols (createDirectory, createFile). ScaReachabilityInit only
    // loads the class - neither vulnerable method is called - so reached must stay empty.
    def reached = reachabilityPayload.get('reached') as List
    assert reached.isEmpty() :
    "createDirectory/createFile not called at startup — reached must be []"
  }
}
