package datadog.common.filesystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import java.io.File;
import java.io.IOException;
import java.security.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

public class FilesTest {

  private SecurityManager originalSM;

  @BeforeEach
  void saveSecurityManager() {
    originalSM = System.getSecurityManager();
  }

  @AfterEach
  void restoreSecurityManager() {
    System.setSecurityManager(originalSM);
  }

  @Test
  void existsReturnsTrueWhenFileExistsAndIsAccessible() throws IOException {
    File file = File.createTempFile("test", "txt");
    file.deleteOnExit();

    assertTrue(Files.exists(file));
  }

  @Test
  void existsReturnsFalseWhenFileDoesNotExist() throws IOException {
    File file = File.createTempFile("missing", "txt");
    file.delete(); // ensure it does not exist

    assertFalse(Files.exists(file));
  }

  @Test
  @DisabledIf("isJava18OrLater")
  void existsReturnsFalseWhenSecurityManagerForbidsFileAccess() throws IOException {
    File file = File.createTempFile("test", "txt");
    file.deleteOnExit();

    // Install restrictive SecurityManager
    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkRead(String filePath) {
            // Deny only THIS file so we don't break classloading
            if (filePath.equals(file.getAbsolutePath())) {
              throw new SecurityException("Access denied");
            }
          }

          @Override
          public void checkPermission(Permission perm) {
            // Allow everything else
          }

          @Override
          public void checkPermission(Permission perm, Object context) {
            // Allow everything else
          }
        });

    boolean result = Files.exists(file);

    assertFalse(result);
  }

  static boolean isJava18OrLater() {
    return JavaVirtualMachine.isJavaVersionAtLeast(18);
  }
}
