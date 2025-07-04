package datadog.trace.api.config;

public final class RumConfig {
  public static final String RUM_ENABLED = "rum.enabled";
  public static final String RUM_APPLICATION_ID = "rum.application.id";
  public static final String RUM_CLIENT_TOKEN = "rum.client.token";
  public static final String RUM_SITE = "rum.site";
  public static final String RUM_SERVICE = "rum.service";
  public static final String RUM_ENVIRONMENT = "rum.environment";
  public static final String RUM_MAJOR_VERSION = "rum.major.version";
  public static final String RUM_VERSION = "rum.version";
  public static final String RUM_TRACK_USER_INTERACTION = "rum.track.user.interaction";
  public static final String RUM_TRACK_RESOURCES = "rum.track.resources";
  public static final String RUM_TRACK_LONG_TASKS = "rum.track.long.tasks";
  public static final String RUM_DEFAULT_PRIVACY_LEVEL = "rum.default.privacy.level";
  public static final String RUM_SESSION_SAMPLE_RATE = "rum.session.sample.rate";
  public static final String RUM_SESSION_REPLAY_SAMPLE_RATE = "rum.session.replay.sample.rate";

  private RumConfig() {}
}
