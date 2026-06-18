package datadog.smoketest.appsec.springboot;

import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Forces snakeyaml's Yaml class to load at application startup so that SCA Reachability can detect
 * the dependency at boot time rather than waiting for the first request.
 *
 * <p>snakeyaml is on the classpath (used by Spring Boot's YAML support) but the smoke test app uses
 * application.properties rather than application.yml, so Spring never triggers snakeyaml loading on
 * its own. This component ensures the class is loaded during context initialization so the SCA
 * transformer can register the CVE and the next telemetry heartbeat can report it.
 */
@Component
class ScaReachabilityInit {

  @PostConstruct
  void init() {
    // Instantiating Yaml loads org.yaml.snakeyaml.Yaml without calling any vulnerable method,
    // so the SCA transformer registers the CVE with reached:[] (class-level detection only).
    new org.yaml.snakeyaml.Yaml();
  }
}
