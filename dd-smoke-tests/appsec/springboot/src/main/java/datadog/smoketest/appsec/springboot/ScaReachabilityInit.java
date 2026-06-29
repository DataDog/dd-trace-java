package datadog.smoketest.appsec.springboot;

import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Forces junrar's LocalFolderExtractor class to load at application startup so that SCA
 * Reachability can detect the dependency at boot time rather than waiting for the first request.
 *
 * <p>The constructor of LocalFolderExtractor is package-private, so Class.forName is used to
 * trigger the class load without instantiation. After the next telemetry heartbeat the SCA
 * transformer retransforms the class and registers the CVE with reached:[]. No vulnerable method is
 * called, so reached stays empty.
 */
@Component
class ScaReachabilityInit {

  @PostConstruct
  void init() {
    try {
      // Loading the class triggers the SCA transformer, which schedules a retransform on the
      // next heartbeat. The retransform injects method-level callbacks and registers the CVE
      // with reached:[]. Neither createDirectory nor createFile is called here.
      Class.forName("com.github.junrar.LocalFolderExtractor");
    } catch (ClassNotFoundException ignored) {
    }
  }
}
