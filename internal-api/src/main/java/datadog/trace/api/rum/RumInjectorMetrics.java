package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class implements the RumTelemetryCollector interface, which is used to collect telemetry
 * from the RumInjector. Metrics are then reported via StatsDClient with tagging.
 *
 * @see <a
 *     href="https://github.com/DataDog/dd-go/blob/prod/trace/apps/tracer-telemetry-intake/telemetry-metrics/static/common_metrics.json">common
 *     metrics and tags</a>
 */
public class RumInjectorMetrics implements RumTelemetryCollector {

  private final AtomicLong injectionSucceed = new AtomicLong();
  private final AtomicLong injectionFailed = new AtomicLong();
  private final AtomicLong injectionSkipped = new AtomicLong();
  private final AtomicLong contentSecurityPolicyDetected = new AtomicLong();
  private final AtomicLong initializationSucceed = new AtomicLong();

  private final StatsDClient statsd;

  private final String applicationId;
  private final String remoteConfigUsed;

  // Cache dependent on servlet version and content encoding
  private final DDCache<String, String[]> succeedTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, String[]> skippedTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, String[]> cspTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, String[]> responseTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, String[]> timeTagsCache = DDCaches.newFixedSizeCache(8);
  private final DDCache<String, String[]> failedTagsCache = DDCaches.newFixedSizeCache(16);

  private static final String[] INIT_TAGS =
      new String[] {"integration_name:servlet", "integration_version:N/A"};

  public RumInjectorMetrics(final StatsDClient statsd) {
    this.statsd = statsd;

    // Get RUM config values (applicationId and remoteConfigUsed) for tagging
    RumInjector rumInjector = RumInjector.get();
    RumInjectorConfig injectorConfig = Config.get().getRumInjectorConfig();
    if (rumInjector.isEnabled() && injectorConfig != null) {
      this.applicationId = injectorConfig.applicationId;
      this.remoteConfigUsed = injectorConfig.remoteConfigurationId != null ? "true" : "false";
    } else {
      this.applicationId = "unknown";
      this.remoteConfigUsed = "false";
    }
  }

  @Override
  public void onInjectionSucceed(String servletVersion) {
    injectionSucceed.incrementAndGet();

    String[] tags =
        succeedTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                new String[] {
                  "application_id:" + applicationId,
                  "integration_name:servlet",
                  "integration_version:" + version,
                  "remote_config_used:" + remoteConfigUsed
                });

    statsd.count("rum.injection.succeed", 1, tags);
  }

  @Override
  public void onInjectionFailed(String servletVersion, String contentEncoding) {
    injectionFailed.incrementAndGet();

    String cacheKey = servletVersion + ":" + contentEncoding;
    String[] tags =
        failedTagsCache.computeIfAbsent(
            cacheKey,
            key -> {
              if (contentEncoding != null) {
                return new String[] {
                  "application_id:" + applicationId,
                  "content_encoding:" + contentEncoding,
                  "integration_name:servlet",
                  "integration_version:" + servletVersion,
                  "reason:failed_to_return_response_wrapper",
                  "remote_config_used:" + remoteConfigUsed
                };
              } else {
                return new String[] {
                  "application_id:" + applicationId,
                  "integration_name:servlet",
                  "integration_version:" + servletVersion,
                  "reason:failed_to_return_response_wrapper",
                  "remote_config_used:" + remoteConfigUsed
                };
              }
            });

    statsd.count("rum.injection.failed", 1, tags);
  }

  @Override
  public void onInjectionSkipped(String servletVersion) {
    injectionSkipped.incrementAndGet();

    String[] tags =
        skippedTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                new String[] {
                  "application_id:" + applicationId,
                  "integration_name:servlet",
                  "integration_version:" + version,
                  "reason:should_not_inject",
                  "remote_config_used:" + remoteConfigUsed
                });

    statsd.count("rum.injection.skipped", 1, tags);
  }

  @Override
  public void onInitializationSucceed() {
    initializationSucceed.incrementAndGet();
    statsd.count("rum.injection.initialization.succeed", 1, INIT_TAGS);
  }

  @Override
  public void onContentSecurityPolicyDetected(String servletVersion) {
    contentSecurityPolicyDetected.incrementAndGet();

    String[] tags =
        cspTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                new String[] {
                  "integration_name:servlet",
                  "integration_version:" + version,
                  "kind:header",
                  "reason:csp_header_found",
                  "status:seen"
                });
    statsd.count("rum.injection.content_security_policy", 1, tags);
  }

  @Override
  public void onInjectionResponseSize(String servletVersion, long bytes) {
    String[] tags =
        responseTagsCache.computeIfAbsent(
            servletVersion,
            version ->
                new String[] {
                  "integration_name:servlet",
                  "integration_version:" + version,
                  "response_kind:header"
                });
    statsd.distribution("rum.injection.response.bytes", bytes, tags);
  }

  @Override
  public void onInjectionTime(String servletVersion, long milliseconds) {
    String[] tags =
        timeTagsCache.computeIfAbsent(
            servletVersion,
            version -> new String[] {"integration_name:servlet", "integration_version:" + version});
    statsd.distribution("rum.injection.ms", milliseconds, tags);
  }

  @Override
  public void close() {
    injectionSucceed.set(0);
    injectionFailed.set(0);
    injectionSkipped.set(0);
    contentSecurityPolicyDetected.set(0);
    initializationSucceed.set(0);

    succeedTagsCache.clear();
    skippedTagsCache.clear();
    cspTagsCache.clear();
    responseTagsCache.clear();
    timeTagsCache.clear();
    failedTagsCache.clear();
  }

  public String summary() {
    return "\ninitializationSucceed="
        + initializationSucceed.get()
        + "\ninjectionSucceed="
        + injectionSucceed.get()
        + "\ninjectionFailed="
        + injectionFailed.get()
        + "\ninjectionSkipped="
        + injectionSkipped.get()
        + "\ncontentSecurityPolicyDetected="
        + contentSecurityPolicyDetected.get();
  }
}
