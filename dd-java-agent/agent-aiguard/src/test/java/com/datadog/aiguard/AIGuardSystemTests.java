package com.datadog.aiguard;

import static datadog.trace.api.aiguard.AIGuardTestHelper.installedEvaluator;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AIGuardSystemTests {

  @AfterEach
  void cleanup() {
    AIGuardInternal.uninstall();
  }

  @Test
  @WithConfig(key = "API_KEY", value = "api", env = true)
  @WithConfig(key = "APP_KEY", value = "app", env = true)
  void testSdkInitialization() {
    AIGuardSystem.start();

    assertInstanceOf(AIGuardInternal.class, installedEvaluator());
  }
}
