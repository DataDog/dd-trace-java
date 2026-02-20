package datadog.trace.api.rum;

import static datadog.trace.api.ConfigDefaults.DEFAULT_RUM_SITE;

import datadog.json.JsonWriter;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class RumInjectorConfig {
  private static final String GOV_CLOUD_SITE = "ddog-gov.com";
  private static final Map<String, String> REGIONS = new HashMap<>();

  static {
    REGIONS.put("datadoghq.com", "us1");
    REGIONS.put("us3.datadoghq.com", "us3");
    REGIONS.put("us5.datadoghq.com", "us5");
    REGIONS.put("datadoghq.eu", "eu1");
    REGIONS.put("ap1.datadoghq.com", "ap1");
    REGIONS.put("ap2.datadoghq.com", "ap2");
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
  public final int majorVersion;

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

  /** The remote configuration identifier. */
  @Nullable public final String remoteConfigurationId;

  /** The JSON representation of injector config to use in the injected SDK snippet. */
  public final String jsonPayload;

  public RumInjectorConfig(
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
      @Nullable Float sessionReplaySampleRate,
      @Nullable String remoteConfigurationId) {
    if (applicationId == null || applicationId.isEmpty()) {
      throw new IllegalArgumentException("Invalid application id: " + applicationId);
    }
    this.applicationId = applicationId;
    if (clientToken == null || clientToken.isEmpty()) {
      throw new IllegalArgumentException("Invalid client token: " + clientToken);
    }
    this.clientToken = clientToken;
    if (site == null || site.isEmpty()) {
      this.site = DEFAULT_RUM_SITE;
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
    this.remoteConfigurationId = remoteConfigurationId;
    if (this.remoteConfigurationId == null
        && (this.sessionSampleRate == null || this.sessionReplaySampleRate == null)) {
      throw new IllegalArgumentException(
          "Either remote configuration id or both session and session replay sample rates must be set");
    }
    this.jsonPayload = jsonPayload();
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
        + "  d=o.createElement(u);d.async=1;d.src=n;d.crossorigin=''\n"
        + "  n=o.getElementsByTagName(u)[0];n.parentNode.insertBefore(d,n)\n"
        + "})(window,document,'script','"
        + getCdnUrl()
        + "','DD_RUM')\n"
        + "window.DD_RUM.onReady(function() {\n"
        + "  window.DD_RUM.init("
        + this.jsonPayload
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

  public String jsonPayload() {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      writer.name("applicationId").value(this.applicationId);
      writer.name("clientToken").value(this.clientToken);
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
        writer.name("trackUserInteractions").value(this.trackUserInteractions);
      }
      if (this.trackResources != null) {
        writer.name("trackResources").value(this.trackResources);
      }
      if (this.trackLongTask != null) {
        writer.name("trackLongTask").value(this.trackLongTask);
      }
      if (this.defaultPrivacyLevel != null) {
        writer.name("defaultPrivacyLevel").value(this.defaultPrivacyLevel.toJson());
      }
      if (this.sessionSampleRate != null) {
        writer.name("sessionSampleRate").value(this.sessionSampleRate);
      }
      if (this.sessionReplaySampleRate != null) {
        writer.name("sessionReplaySampleRate").value(this.sessionReplaySampleRate);
      }
      if (this.remoteConfigurationId != null) {
        writer.name("remoteConfigurationId").value(this.remoteConfigurationId);
      }
      writer.endObject();
      return writer.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Fail to generate config payload", e);
    }
  }

  public enum PrivacyLevel {
    ALLOW("allow"),
    MASK("mask"),
    MASK_USER_INPUT("mask-user-input");

    private final String json;

    PrivacyLevel(String json) {
      this.json = json;
    }

    public String toJson() {
      return this.json;
    }
  }
}
