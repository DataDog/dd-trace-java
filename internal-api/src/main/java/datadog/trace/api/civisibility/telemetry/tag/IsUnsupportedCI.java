package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/**
 * Whether a test session executes in an unsupported CI provider. "Supporting" means extracting run
 * metadata (Git and pipeline info) from environment variables (see {@code
 * datadog.trace.civisibility.ci.CIProviderInfo} implementations for list of supported providers).
 */
public enum IsUnsupportedCI implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_unsupported_ci:true";
  }
}
