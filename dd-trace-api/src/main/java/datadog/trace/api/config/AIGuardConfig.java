package datadog.trace.api.config;

public final class AIGuardConfig {

  public static final String AI_GUARD_ENABLED = "ai_guard.enabled";
  public static final String AI_GUARD_ENDPOINT = "ai_guard.endpoint";
  public static final String AI_GUARD_TIMEOUT = "ai_guard.timeout";
  public static final String AI_GUARD_MAX_CONTENT_SIZE = "ai_guard.max-content-size";
  public static final String AI_GUARD_MAX_MESSAGES_LENGTH = "ai_guard.max-messages-length";

  public static final boolean DEFAULT_AI_GUARD_ENABLED = false;
  public static final int DEFAULT_AI_GUARD_TIMEOUT = 10_000;
  public static final int DEFAULT_AI_GUARD_MAX_CONTENT_SIZE = 512 * 1024;
  public static final int DEFAULT_AI_GUARD_MAX_MESSAGES_LENGTH = 16;
}
