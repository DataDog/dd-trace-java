package datadog.remoteconfig;

public final class Capabilities {
  // This bitset is reserved according to the Remote Config spec.
  public static final long CAPABILITY_ASM_ACTIVATION = 1 << 1;
  public static final long CAPABILITY_ASM_IP_BLOCKING = 1 << 2;
  public static final long CAPABILITY_ASM_DD_RULES = 1 << 3;
  public static final long CAPABILITY_ASM_EXCLUSIONS = 1 << 4;
  public static final long CAPABILITY_ASM_REQUEST_BLOCKING = 1 << 5;
  public static final long CAPABILITY_ASM_RESPONSE_BLOCKING = 1 << 6;
  public static final long CAPABILITY_ASM_USER_BLOCKING = 1 << 7;
  public static final long CAPABILITY_ASM_CUSTOM_RULES = 1 << 8;
  public static final long CAPABILITY_ASM_CUSTOM_BLOCKING_RESPONSE = 1 << 9;
  public static final long CAPABILITY_ASM_TRUSTED_IPS = 1 << 10;
  public static final long CAPABILITY_ASM_API_SECURITY_SAMPLE_RATE = 1 << 11;
  public static final long CAPABILITY_APM_TRACING_SAMPLE_RATE = 1 << 12;
  public static final long CAPABILITY_APM_LOGS_INJECTION = 1 << 13;
  public static final long CAPABILITY_APM_HTTP_HEADER_TAGS = 1 << 14;
  public static final long CAPABILITY_APM_CUSTOM_TAGS = 1 << 15;
  public static final long CAPABILITY_ASM_PROCESSOR_OVERRIDES = 1 << 16;
  public static final long CAPABILITY_ASM_CUSTOM_DATA_SCANNERS = 1 << 17;
  public static final long CAPABILITY_ASM_EXCLUSION_DATA = 1 << 18;
  public static final long CAPABILITY_APM_TRACING_TRACING_ENABLED = 1 << 19;
  public static final long CAPABILITY_APM_TRACING_DATA_STREAMS_ENABLED = 1 << 20;
  public static final long CAPABILITY_ASM_RASP_SQLI = 1 << 21;
  public static final long CAPABILITY_ASM_RASP_LFI = 1 << 22;
  public static final long CAPABILITY_ASM_RASP_SSRF = 1 << 23;
  public static final long CAPABILITY_ASM_RASP_SHI = 1 << 24;
  public static final long CAPABILITY_ASM_RASP_XXE = 1 << 25;
  public static final long CAPABILITY_ASM_RASP_RCE = 1 << 26;
  public static final long CAPABILITY_ASM_RASP_NOSQLI = 1 << 27;
  public static final long CAPABILITY_ASM_RASP_XSS = 1 << 28;
  public static final long CAPABILITY_APM_TRACING_SAMPLE_RULES = 1 << 29;

  private Capabilities() {}
}
