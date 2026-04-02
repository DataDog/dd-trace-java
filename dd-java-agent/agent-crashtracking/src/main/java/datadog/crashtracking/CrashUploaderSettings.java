package datadog.crashtracking;

/**
 * Crash-tracking-specific settings controlling which sections are included in parsed crash reports.
 */
public final class CrashUploaderSettings {

  private final boolean registerMappingEnabled;

  public CrashUploaderSettings(boolean registerMappingEnabled) {
    this.registerMappingEnabled = registerMappingEnabled;
  }

  /** Whether the register-to-memory mapping section should be included in parsed crash reports. */
  public boolean isRegisterMappingEnabled() {
    return registerMappingEnabled;
  }
}
