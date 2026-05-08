package com.datadog.aiguard;

import static datadog.trace.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AIGuardSystemTests extends DDJavaSpecification {

  @AfterEach
  void uninstallEvaluator() {
    AIGuardInternal.uninstall();
  }

  @Test
  void testSdkInitialization() throws ReflectiveOperationException {
    injectEnvConfig("API_KEY", "api");
    injectEnvConfig("APP_KEY", "app");

    AIGuardSystem.start();

    Field evaluator = AIGuard.class.getDeclaredField("EVALUATOR");
    evaluator.setAccessible(true);
    assertTrue(evaluator.get(null) instanceof AIGuardInternal);
  }
}
