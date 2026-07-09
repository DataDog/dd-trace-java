package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.junit.utils.config.WithConfig;
import datadog.trace.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class ConfigSecureRandomTest {

  @Test
  void defaultStrategyIsRandom() {
    assertNotEquals("SRandom", Config.get().getIdGenerationStrategy().getClass().getSimpleName());
  }

  @Test
  void snapStartEnablesSecureRandom() {
    WithConfigExtension.injectEnvConfig("AWS_LAMBDA_INITIALIZATION_TYPE", "snap-start", false);

    assertEquals("SRandom", Config.get().getIdGenerationStrategy().getClass().getSimpleName());
  }

  @Test
  void microvmImageArnEnablesSecureRandom() {
    WithConfigExtension.injectEnvConfig(
        "AWS_LAMBDA_MICROVM_IMAGE_ARN", "arn:aws:lambda:us-east-1::runtime:microvm", false);

    assertEquals("SRandom", Config.get().getIdGenerationStrategy().getClass().getSimpleName());
  }

  @Test
  void emptyMicrovmImageArnDoesNotEnableSecureRandom() {
    WithConfigExtension.injectEnvConfig("AWS_LAMBDA_MICROVM_IMAGE_ARN", "", false);

    assertNotEquals("SRandom", Config.get().getIdGenerationStrategy().getClass().getSimpleName());
  }

  @Test
  @WithConfig(key = "trace.secure-random", value = "true")
  void configPropertyEnablesSecureRandom() {
    assertEquals("SRandom", Config.get().getIdGenerationStrategy().getClass().getSimpleName());
  }
}
