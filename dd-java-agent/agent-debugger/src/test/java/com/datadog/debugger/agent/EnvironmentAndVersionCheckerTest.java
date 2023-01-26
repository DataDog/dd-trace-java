package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EnvironmentAndVersionCheckerTest {

  private static final String SERVICE_ENV_PROD = "prod";
  private static final String SERVICE_ENV_PROD_CAP = "PROD";
  private static final String SERVICE_VERSION_1 = "v1.0.0";
  private static final String SERVICE_VERSION_2 = "v1.0.0-SNAPSHOT";

  @Mock private Config config;

  @Test
  public void EmptyTagsPassValidation() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(true, checker.isEnvAndVersionMatch(getProbeWithTags()));
  }

  @Test
  public void EnvironmentTagPassValidation() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(true, checker.isEnvAndVersionMatch(getProbeWithTags("env:" + SERVICE_ENV_PROD)));
  }

  @Test
  public void EnvironmentTagFailedValidation() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(false, checker.isEnvAndVersionMatch(getProbeWithTags("env:staging")));
    assertEquals(
        false,
        checker.isEnvAndVersionMatch(
            getProbeWithTags("env:staging", "version:" + SERVICE_VERSION_1)));
  }

  @Test
  public void EnvironmentTagPassValidationAfterSanitization() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD_CAP);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(true, checker.isEnvAndVersionMatch(getProbeWithTags("env:" + SERVICE_ENV_PROD)));
  }

  @Test
  public void VersionTagFailedValidation() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(false, checker.isEnvAndVersionMatch(getProbeWithTags("version:1.2.3")));
    assertEquals(
        false,
        checker.isEnvAndVersionMatch(getProbeWithTags("env:" + SERVICE_ENV_PROD, "version:1.2.3")));
  }

  @Test
  public void VersionTagPassValidation() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_1);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(
        true, checker.isEnvAndVersionMatch(getProbeWithTags("version:" + SERVICE_VERSION_1)));
    assertEquals(
        true,
        checker.isEnvAndVersionMatch(
            getProbeWithTags("env:" + SERVICE_ENV_PROD, "version:" + SERVICE_VERSION_1)));
  }

  @Test
  public void VersionTagPassValidationAfterSanitization() {
    lenient().when(config.getEnv()).thenReturn(SERVICE_ENV_PROD_CAP);
    lenient().when(config.getVersion()).thenReturn(SERVICE_VERSION_2);

    EnvironmentAndVersionChecker checker = new EnvironmentAndVersionChecker(config);

    assertEquals(
        true,
        checker.isEnvAndVersionMatch(
            getProbeWithTags("version:" + SERVICE_VERSION_2.toLowerCase())));
  }

  private ProbeDefinition getProbeWithTags(String... tags) {
    return LogProbe.builder().probeId("1").tags(tags).build();
  }
}
