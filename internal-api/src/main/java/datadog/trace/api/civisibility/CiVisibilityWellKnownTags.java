package datadog.trace.api.civisibility;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

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
    this.runtimeId = UTF8BytesString.create(runtimeId);
    this.env = UTF8BytesString.create(env);
    this.language = UTF8BytesString.create(language);
    this.runtimeName = UTF8BytesString.create(runtimeName);
    this.runtimeVersion = UTF8BytesString.create(runtimeVersion);
    this.runtimeVendor = UTF8BytesString.create(runtimeVendor);
    this.osArch = UTF8BytesString.create(osArch);
    this.osPlatform = UTF8BytesString.create(osPlatform);
    this.osVersion = UTF8BytesString.create(osVersion);
    this.isUserProvidedService = UTF8BytesString.create(isUserProvidedService);
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
