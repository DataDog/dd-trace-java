package datadog.trace.api;

/** The Feature Flags configuration source. */
public enum FeatureFlaggingSource {
  /** No source is configured. */
  UNSET,
  /** The configured source is not supported. */
  INVALID,
  /** The flag configuration is polled from the Datadog CDN. */
  AGENTLESS,
  /** The flag configuration is received through Remote Configuration. */
  REMOTE_CONFIG
}
