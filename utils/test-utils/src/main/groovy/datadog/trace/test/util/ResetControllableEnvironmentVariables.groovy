package datadog.trace.test.util

import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * This rule is needed to surface the reset method.  The base class runs after
 * DDSpecification.cleanup() not allowing it to rebuild Config with the restored environment
 */
class ResetControllableEnvironmentVariables extends EnvironmentVariables {
  def delegate

  @Override
  Statement apply(Statement base, Description description) {
    delegate = super.apply(base, description)

    return delegate
  }

  void reset() {
    delegate?.restoreOriginalVariables()
  }
}
