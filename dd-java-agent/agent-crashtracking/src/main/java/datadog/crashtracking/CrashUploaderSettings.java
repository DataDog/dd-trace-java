package datadog.crashtracking;

/** Immutable settings that control what data {@link CrashUploader} includes in uploaded reports. */
public final class CrashUploaderSettings {
  final boolean extendedInfoEnabled;

  CrashUploaderSettings(boolean extendedInfoEnabled) {
    this.extendedInfoEnabled = extendedInfoEnabled;
  }

  boolean isExtendedInfoEnabled() {
    return extendedInfoEnabled;
  }
}
