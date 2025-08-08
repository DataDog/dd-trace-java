package datadog.trace.api.rum;

import datadog.trace.api.StatsDClient;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This class implements the RumTelemetryCollector interface, which is used to collect telemetry
// from the RumInjector. Metrics are then reported via StatsDClient with tagging. See:
// https://github.com/DataDog/dd-go/blob/prod/trace/apps/tracer-telemetry-intake/telemetry-metrics/static/common_metrics.json
// for common metrics and tags.
public class RumInjectorMetrics implements RumTelemetryCollector {
  private static final Logger log = LoggerFactory.getLogger(RumInjectorMetrics.class);

  private static final String[] NO_TAGS = new String[0];

  // Use static tags for common combinations so that we don't have to build them for each metric
  private static final String[] CSP_SERVLET3_TAGS =
      new String[] {
        "injector_version:0.1.0",
        "integration_name:servlet",
        "integration_version:3",
        "kind:header",
        "reason:csp_header_found",
        "status:seen"
      };

  private static final String[] CSP_SERVLET5_TAGS =
      new String[] {
        "injector_version:0.1.0",
        "integration_name:servlet",
        "integration_version:5",
        "kind:header",
        "reason:csp_header_found",
        "status:seen"
      };

  private static final String[] INIT_TAGS =
      new String[] {
        "injector_version:0.1.0", "integration_name:servlet", "integration_version:3,5"
      };

  private static final String[] TIME_SERVLET3_TAGS =
      new String[] {"injector_version:0.1.0", "integration_name:servlet", "integration_version:3"};

  private static final String[] TIME_SERVLET5_TAGS =
      new String[] {"injector_version:0.1.0", "integration_name:servlet", "integration_version:5"};

  private static final String[] RESPONSE_SERVLET3_TAGS =
      new String[] {
        "injector_version:0.1.0",
        "integration_name:servlet",
        "integration_version:3",
        "response_kind:header"
      };

  private static final String[] RESPONSE_SERVLET5_TAGS =
      new String[] {
        "injector_version:0.1.0",
        "integration_name:servlet",
        "integration_version:5",
        "response_kind:header"
      };

  private final AtomicLong injectionSucceed = new AtomicLong();
  private final AtomicLong injectionFailed = new AtomicLong();
  private final AtomicLong injectionSkipped = new AtomicLong();
  private final AtomicLong contentSecurityPolicyDetected = new AtomicLong();
  private final AtomicLong initializationSucceed = new AtomicLong();

  private final StatsDClient statsd;

  private final String applicationId;
  private final String remoteConfigUsed;

  public RumInjectorMetrics(final StatsDClient statsd) {
    this.statsd = statsd;

    // Get RUM config values (applicationId and remoteConfigUsed) for tagging
    RumInjector rumInjector = RumInjector.get();
    if (rumInjector.isEnabled()) {
      datadog.trace.api.Config config = datadog.trace.api.Config.get();
      RumInjectorConfig injectorConfig = config.getRumInjectorConfig();
      if (injectorConfig != null) {
        this.applicationId = injectorConfig.applicationId;
        this.remoteConfigUsed = injectorConfig.remoteConfigurationId != null ? "true" : "false";
      } else {
        this.applicationId = "unknown";
        this.remoteConfigUsed = "false";
      }
    } else {
      this.applicationId = "unknown";
      this.remoteConfigUsed = "false";
    }
  }

  @Override
  public void onInjectionSucceed(String integrationVersion) {
    injectionSucceed.incrementAndGet();

    String[] tags =
        new String[] {
          "application_id:" + applicationId,
          "injector_version:0.1.0",
          "integration_name:servlet",
          "integration_version:" + integrationVersion,
          "remote_config_used:" + remoteConfigUsed
        };

    statsd.count("rum.injection.succeed", 1, tags);
  }

  @Override
  public void onInjectionFailed(String integrationVersion, String contentEncoding) {
    injectionFailed.incrementAndGet();

    String[] tags =
        new String[] {
          "application_id:" + applicationId,
          "content_encoding:" + contentEncoding,
          "injector_version:0.1.0",
          "integration_name:servlet",
          "integration_version:" + integrationVersion,
          "reason:failed_to_return_response_wrapper",
          "remote_config_used:" + remoteConfigUsed
        };

    statsd.count("rum.injection.failed", 1, tags);
  }

  @Override
  public void onInjectionSkipped(String integrationVersion) {
    injectionSkipped.incrementAndGet();

    String[] tags =
        new String[] {
          "application_id:" + applicationId,
          "injector_version:0.1.0",
          "integration_name:servlet",
          "integration_version:" + integrationVersion,
          "reason:should_not_inject",
          "remote_config_used:" + remoteConfigUsed
        };

    statsd.count("rum.injection.skipped", 1, tags);
  }

  @Override
  public void onInitializationSucceed() {
    initializationSucceed.incrementAndGet();
    statsd.count("rum.injection.initialization.succeed", 1, INIT_TAGS);
  }

  @Override
  public void onContentSecurityPolicyDetected(String integrationVersion) {
    contentSecurityPolicyDetected.incrementAndGet();

    String[] tags = "5".equals(integrationVersion) ? CSP_SERVLET5_TAGS : CSP_SERVLET3_TAGS;
    statsd.count("rum.injection.content_security_policy", 1, tags);
  }

  @Override
  public void onInjectionResponseSize(String integrationVersion, long bytes) {
    String[] tags =
        "5".equals(integrationVersion) ? RESPONSE_SERVLET5_TAGS : RESPONSE_SERVLET3_TAGS;
    statsd.distribution("rum.injection.response.bytes", bytes, tags);
  }

  @Override
  public void onInjectionTime(String integrationVersion, long milliseconds) {
    String[] tags = "5".equals(integrationVersion) ? TIME_SERVLET5_TAGS : TIME_SERVLET3_TAGS;
    statsd.distribution("rum.injection.ms", milliseconds, tags);
  }

  @Override
  public void close() {
    injectionSucceed.set(0);
    injectionFailed.set(0);
    injectionSkipped.set(0);
    contentSecurityPolicyDetected.set(0);
    initializationSucceed.set(0);
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
