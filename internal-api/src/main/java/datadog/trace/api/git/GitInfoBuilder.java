package datadog.trace.api.git;

import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import javax.annotation.Nullable;

public interface GitInfoBuilder {
  GitInfo build(@Nullable String repositoryPath);

  int order();

  /**
   * Used for SHA discrepancies telemetry. Two enums are needed, one for each tag:
   * `expected_provider`, `discrepant_provider`. A provider can act as either of them depending on
   * the discrepancy found.
   */
  GitProviderExpected providerAsExpected();

  GitProviderDiscrepant providerAsDiscrepant();
}
