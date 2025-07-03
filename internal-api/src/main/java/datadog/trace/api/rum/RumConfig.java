package datadog.trace.api.rum;

import static java.util.Locale.ROOT;

import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class RumConfig {
  private static final String DEFAULT_SITE = "datadoghq.com";
  private static final String GOV_CLOUD_SITE = "ddog-gov.com";
  private static final Map<String, String> REGIONS = new HashMap<>();

  static {
    REGIONS.put("datadoghq.com", "us1");
    REGIONS.put("us3.datadoghq.com", "us3");
    REGIONS.put("us5.datadoghq.com", "us5");
    REGIONS.put("datadoghq.eu", "eu1");
    REGIONS.put("ap1.datadoghq.com", "ap1");
  }

  /** RUM application ID */
  public final String applicationId;
  /** The client token provided by Datadog to authenticate requests. */
  public final String clientToken;
  /** The Datadog site to which data will be sent (e.g., `datadoghq.com`). */
  public final String site;
  /** The name of the service being monitored. */
  @Nullable public final String service;
  /** The environment of the service (e.g., `prod`, `staging` or `dev). */
  @Nullable public final String env;
  /** SDK major version. */
  public int majorVersion;
  /** The version of the service (e.g., `0.1.0`, `a8dj92`, `2024-30`). */
  @Nullable public final String version;
  /** Enables or disables the automatic collection of users actions (e.g., clicks). */
  @Nullable public final Boolean trackUserInteractions;
  /** Enables or disables the collection of resource events (e.g., loading of images or scripts). */
  @Nullable public final Boolean trackResources;
  /** Enables or disables the collection of long task events. */
  @Nullable public final Boolean trackLongTask;
  /** The privacy level for data collection. */
  @Nullable public final PrivacyLevel defaultPrivacyLevel;
  /** The percentage of user sessions to be tracked (between 0.0 and 100.0). */
  @Nullable public final Float sessionSampleRate;
  /**
   * The percentage of tracked sessions that will include Session Replay data (between 0.0 and
   * 100.0).
   */
  @Nullable public final Float sessionReplaySampleRate;

  public static RumConfig from(Config config) {
    // TODO
    return null;
  }

  RumConfig(
      String applicationId,
      String clientToken,
      @Nullable String site,
      @Nullable String service,
      @Nullable String env,
      int majorVersion,
      @Nullable String version,
      @Nullable Boolean trackUserInteractions,
      @Nullable Boolean trackResources,
      @Nullable Boolean trackLongTask,
      @Nullable PrivacyLevel defaultPrivacyLevel,
      @Nullable Float sessionSampleRate,
      @Nullable Float sessionReplaySampleRate) {
    if (applicationId == null || applicationId.isEmpty()) {
      throw new IllegalArgumentException("Invalid application id: " + applicationId);
    }
    this.applicationId = applicationId;
    if (clientToken == null || clientToken.isEmpty()) {
      throw new IllegalArgumentException("Invalid client token: " + clientToken);
    }
    this.clientToken = clientToken;
    if (site == null || site.isEmpty()) {
      this.site = DEFAULT_SITE;
    } else if (validateSite(site)) {
      this.site = site;
    } else {
      throw new IllegalArgumentException("Invalid site: " + site);
    }
    this.service = service;
    this.env = env;
    if (majorVersion != 5 && majorVersion != 6) {
      throw new IllegalArgumentException("Invalid major version: " + majorVersion);
    }
    this.majorVersion = majorVersion;
    this.version = version;
    this.trackUserInteractions = trackUserInteractions;
    this.trackResources = trackResources;
    this.trackLongTask = trackLongTask;
    if (sessionSampleRate != null && (sessionSampleRate < 0f || sessionSampleRate > 100f)) {
      throw new IllegalArgumentException("Invalid session sample rate: " + sessionSampleRate);
    }
    this.sessionSampleRate = sessionSampleRate;
    if (sessionReplaySampleRate != null
        && (sessionReplaySampleRate < 0f || sessionReplaySampleRate > 100f)) {
      throw new IllegalArgumentException(
          "Invalid session replay sample rate: " + sessionReplaySampleRate);
    }
    this.sessionReplaySampleRate = sessionReplaySampleRate;
    this.defaultPrivacyLevel = defaultPrivacyLevel;
  }

  private static boolean validateSite(String site) {
    for (String key : REGIONS.keySet()) {
      if (key.equals(site)) {
        return true;
      }
    }
    return false;
  }

  public String getSnippet() {
    return "<script>\n"
        + "(function(h,o,u,n,d) {\n"
        + "  h=h[d]=h[d]||{q:[],onReady:function(c){h.q.push(c)}}\n"
        + "  d=o.createElement(u);d.async=1;d.src=n\n"
        + "  n=o.getElementsByTagName(u)[0];n.parentNode.insertBefore(d,n)\n"
        + "})(window,document,'script','"
        + getCdnUrl()
        + "','DD_RUM')\n"
        + "window.DD_RUM.onReady(function() {\n"
        + "  window.DD_RUM.init("
        + jsonPayload()
        + ");\n"
        + "});\n"
        + "</script>\n";
  }

  private String getCdnUrl() {
    if (!GOV_CLOUD_SITE.equals(this.site)) {
      return "https://www.datadoghq-browser-agent.com/datadog-rum-v" + this.majorVersion + ".js";
    }
    return "https://www.datadoghq-browser-agent.com/"
        + REGIONS.get(this.site)
        + "/v"
        + this.majorVersion
        + "/datadog-rum.js";
  }

  private String jsonPayload() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("application_id").value(this.applicationId);
      writer.name("client_token").value(this.clientToken);
      if (this.site != null) {
        writer.name("site").value(this.site);
      }
      if (this.service != null) {
        writer.name("service").value(this.service);
      }
      if (this.env != null) {
        writer.name("env").value(this.env);
      }
      if (this.version != null) {
        writer.name("version").value(this.version);
      }
      if (this.trackUserInteractions != null) {
        writer.name("track_user_interactions").value(this.trackUserInteractions);
      }
      if (this.trackResources != null) {
        writer.name("track_resources").value(this.trackResources);
      }
      if (this.trackLongTask != null) {
        writer.name("track_long_task").value(this.trackLongTask);
      }
      if (this.defaultPrivacyLevel != null) {
        writer.name("default_privacy_level").value(this.defaultPrivacyLevel.toJson());
      }
      if (this.sessionSampleRate != null) {
        writer.name("session_sample_rate").value(this.sessionSampleRate);
      }
      if (this.sessionReplaySampleRate != null) {
        writer.name("session_replay_sample_rate").value(this.sessionReplaySampleRate);
      }
      return writer.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Fail to generate config payload", e);
    }
  }

  public enum PrivacyLevel {
    ALLOW,
    MASK,
    MASK_USER_INPUT;

    public String toJson() {
      return toString().toLowerCase(ROOT);
    }
  }
}
