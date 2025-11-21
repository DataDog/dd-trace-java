package datadog.common.filesystem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import java.io.File;
import java.io.IOException;
import java.security.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

public class FilesTest {

  private SecurityManager originalSM;

  @Test
  void existsReturnsTrueWhenFileExistsAndIsAccessible() throws IOException {
    File file = File.createTempFile("test", "txt");
    file.deleteOnExit();

    assertTrue(Files.exists(file));
  }

  @Test
  void existsReturnsFalseWhenFileDoesNotExist() throws IOException {
    File file = File.createTempFile("missing", "txt");
    assertTrue(file.delete()); // ensure it does not exist

    assertFalse(Files.exists(file));
  }

  @Test
  @DisabledIf("isJava18OrLater")
  void existsReturnsFalseWhenSecurityManagerForbidsFileAccess() throws IOException {
    File file = File.createTempFile("test", "txt");
    file.deleteOnExit();

    // --- install restrictive SecurityManager only in this test ---
    SecurityManager originalSM = System.getSecurityManager();

    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkRead(String filePath) {
            // Deny only THIS file so classloading still works
            if (filePath.equals(file.getAbsolutePath())) {
              throw new SecurityException("Access denied");
            }
          }

          @Override
          public void checkPermission(Permission perm) {
            // allow everything else
          }

          @Override
          public void checkPermission(Permission perm, Object context) {
            // allow everything else
          }
        });

    try {
      boolean result = Files.exists(file);
      assertFalse(result);
    } finally {
      // --- restore original security manager ---
      System.setSecurityManager(originalSM);
    }
  }

  static boolean isJava18OrLater() {
    return JavaVirtualMachine.isJavaVersionAtLeast(18);
  }
}
