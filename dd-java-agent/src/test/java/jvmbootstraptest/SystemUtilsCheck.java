package jvmbootstraptest;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.PropertyPermission;

public final class SystemUtilsCheck {
  public static void main(String[] args) {}

  public static class BlockEnvVar extends TestSecurityManager {
    @Override
    protected boolean checkRuntimeEnvironmentAccess(
        RuntimePermission perm, Object ctx, String envVar) {
      // SOMETHING HERE ??
      return super.checkRuntimeEnvironmentAccess(perm, ctx, envVar);
    }
  }

  public static class BlockPropertyVar extends TestSecurityManager {
    @Override
    protected boolean checkPropertyReadPermission(
        PropertyPermission perm, Object ctx, String property) {
      // SOMETHING HERE ??
      return minimalCheckPropertyReadPermission(perm, ctx, property);
    }

    @Override
    protected boolean checkPropertyWritePermission(
        PropertyPermission perm, Object ctx, String property) {
      // SOMETHING HERE ??
      return false;
    }
  }

  public static final int runTestJvm(Class<? extends TestSecurityManager> securityManagerClass)
      throws Exception {
    return runTestJvm(securityManagerClass, false);
  }

  public static final int runTestJvm(
      Class<? extends TestSecurityManager> securityManagerClass, boolean printStreams)
      throws Exception {

    File jarFile =
        IntegrationTestUtils.createJarFileWithClasses(requiredClasses(securityManagerClass));

    try {
      return IntegrationTestUtils.runOnSeparateJvm(
          SystemUtilsCheck.class.getName(),
          SystemUtilsCheck.jvmArgs(securityManagerClass),
          SystemUtilsCheck.mainArgs(),
          SystemUtilsCheck.envVars(),
          jarFile,
          printStreams);
    } finally {
      jarFile.delete();
    }
  }

  public static final Class<?>[] requiredClasses(
      Class<? extends TestSecurityManager> securityManagerClass) {
    return new Class<?>[] {
      SystemUtilsCheck.class,
      securityManagerClass,
      TestSecurityManager.class,
      CustomSecurityManager.class
    };
  }

  public static final String[] jvmArgs(Class<? extends TestSecurityManager> securityManagerClass) {
    return new String[] {"-Djava.security.manager=" + securityManagerClass.getName()};
  }

  public static final String[] mainArgs() {
    return new String[] {};
  }

  public static final Map<String, String> envVars() {
    return Collections.emptyMap();
  }
}
