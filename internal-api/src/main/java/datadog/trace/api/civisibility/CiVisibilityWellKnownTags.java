package datadog.trace.api.civisibility;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Strings;

public class CiVisibilityWellKnownTags {

  private final UTF8BytesString runtimeId;
  private final UTF8BytesString env;
  private final UTF8BytesString language;
  private final UTF8BytesString runtimeName;
  private final UTF8BytesString runtimeVersion;
  private final UTF8BytesString runtimeVendor;
  private final UTF8BytesString osArch;
  private final UTF8BytesString osPlatform;
  private final UTF8BytesString osVersion;
  private final UTF8BytesString isUserProvidedService;

  public CiVisibilityWellKnownTags(
      CharSequence runtimeId,
      CharSequence env,
      CharSequence language,
      CharSequence runtimeName,
      CharSequence runtimeVersion,
      CharSequence runtimeVendor,
      CharSequence osArch,
      CharSequence osPlatform,
      CharSequence osVersion,
      CharSequence isUserProvidedService) {
    this.runtimeId = truncated(runtimeId);
    this.env = truncated(env);
    this.language = truncated(language);
    this.runtimeName = truncated(runtimeName);
    this.runtimeVersion = truncated(runtimeVersion);
    this.runtimeVendor = truncated(runtimeVendor);
    this.osArch = truncated(osArch);
    this.osPlatform = truncated(osPlatform);
    this.osVersion = truncated(osVersion);
    this.isUserProvidedService = truncated(isUserProvidedService);
  }

  /**
   * Truncates a well-known tag value to the EVP per-value limit once, up front, and stores it
   * pre-encoded so the intake serializers can marshal it as-is on every payload without truncating.
   */
  private static UTF8BytesString truncated(CharSequence value) {
    return UTF8BytesString.create(
        Strings.truncate(value, CIConstants.MAX_META_STRING_VALUE_LENGTH));
  }

  public UTF8BytesString getEnv() {
    return env;
  }

  public UTF8BytesString getLanguage() {
    return language;
  }

  public UTF8BytesString getOsArch() {
    return osArch;
  }

  public UTF8BytesString getOsPlatform() {
    return osPlatform;
  }

  public UTF8BytesString getOsVersion() {
    return osVersion;
  }

  public UTF8BytesString getRuntimeId() {
    return runtimeId;
  }

  public UTF8BytesString getRuntimeName() {
    return runtimeName;
  }

  public UTF8BytesString getRuntimeVersion() {
    return runtimeVersion;
  }

  public UTF8BytesString getRuntimeVendor() {
    return runtimeVendor;
  }

  public UTF8BytesString getIsUserProvidedService() {
    return isUserProvidedService;
  }
}
