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
 * The springboot smoke test app uses snakeyaml:1.29 (via Spring Boot 2.6.0), which falls
 * in the vulnerable range "<= 1.33" for GHSA-mjmj-j48q-9wg2. ScaReachabilityInit instantiates
 * org.yaml.snakeyaml.Yaml at startup (PostConstruct), triggering class-level CVE registration
 * with reached:[]. The vulnerable methods (load, loadAll) are not called, so reached stays empty.
 *
 * Note: jackson-databind is also present but as version 2.13.0 (managed by Spring Boot BOM),
 * which is outside the vulnerable ranges in sca_cves.json. snakeyaml is therefore the
 * reliable test target.
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

  void 'SCA reachability reports vulnerable snakeyaml via telemetry'() {
    when: 'application starts and telemetry heartbeats arrive'
    waitForTelemetryFlat({ event ->
      if (event.get('request_type') != 'app-dependencies-loaded') {
        return false
      }
      def deps = event.get('payload')?.get('dependencies') as List
      deps?.any { dep ->
        def d = dep as Map
        d.get('name') == 'org.yaml:snakeyaml' &&
          (d.get('metadata') as List)?.any { (it as Map).get('type') == 'reachability' }
      }
    })

    then: 'snakeyaml 1.29 appears with SCA reachability metadata'
    // Collect all dependencies from all app-dependencies-loaded messages
    def allDependencies = []
    telemetryFlatMessages.findAll { it.get('request_type') == 'app-dependencies-loaded' }.each {
      def payload = it.get('payload') as Map
      def deps = payload?.get('dependencies') as List
      if (deps) {
        allDependencies.addAll(deps)
      }
    }

    // Find the snakeyaml entry that has SCA reachability metadata.
    // ScaReachabilityInit instantiates Yaml at startup (PostConstruct), loading the class
    // and triggering CVE registration with reached:[].

    def snakeyamlDep = allDependencies.find { dep ->
      def d = dep as Map
      d.get('name') == 'org.yaml:snakeyaml' &&
        (d.get('metadata') as List)?.any { (it as Map).get('type') == 'reachability' }
    } as Map

    assert snakeyamlDep != null :
    "snakeyaml must appear with SCA reachability metadata in app-dependencies-loaded"
    assert snakeyamlDep.get('version') == '1.29' : "must be the vulnerable version 1.29"

    // Find the reachability metadata entry
    def metadata = snakeyamlDep.get('metadata') as List
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

    // snakeyaml has method-level symbols (load, loadAll). ScaReachabilityInit calls new Yaml()
    // (constructor only) so load/loadAll are never triggered — reached must stay empty.
    def reached = reachabilityPayload.get('reached') as List
    assert reached.isEmpty() :
    "load/loadAll not called at startup — reached must be [] (class-level detection only)"
  }
}
