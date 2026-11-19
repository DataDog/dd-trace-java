package datadog.trace.agent;

import static jvmbootstraptest.SecurityManagerCheck.runTestJvm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.condition.JRE.JAVA_24;

import jvmbootstraptest.TestSecurityManager.MinimalPropertyAccess;
import jvmbootstraptest.TestSecurityManager.NoEnvAccess;
import jvmbootstraptest.TestSecurityManager.NoNetworkAccess;
import jvmbootstraptest.TestSecurityManager.NoProcessExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledForJreRange;

@Timeout(30)
@DisabledForJreRange(
    min = JAVA_24,
    disabledReason = "SecurityManager is permanently disabled as of JDK 24")
class SecurityManagerTest {

  @Test
  void noEnvAccess() throws Exception {
    assertEquals(0, runTestJvm(NoEnvAccess.class));
  }

  @Test
  void minimalPropertyAccess() throws Exception {
    assertEquals(0, runTestJvm(MinimalPropertyAccess.class, true));
  }

  @Test
  void noProcessExecution() throws Exception {
    assertEquals(0, runTestJvm(NoProcessExecution.class));
  }

  @Test
  void noNetworkAccess() throws Exception {
    assertEquals(0, runTestJvm(NoNetworkAccess.class));
  }
}
