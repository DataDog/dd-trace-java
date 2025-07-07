package jvmbootstraptest;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class SecurityManagerCheck {
  public static void main(String[] args) {}

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
          SecurityManagerCheck.class.getName(),
          SecurityManagerCheck.jvmArgs(securityManagerClass),
          SecurityManagerCheck.mainArgs(),
          SecurityManagerCheck.envVars(),
          jarFile,
          printStreams);
    } finally {
      jarFile.delete();
    }
  }

  public static final Class<?>[] requiredClasses(
      Class<? extends TestSecurityManager> securityManagerClass) {
    return new Class<?>[] {
      SecurityManagerCheck.class,
      securityManagerClass,
      TestSecurityManager.class,
      CustomSecurityManager.class
    };
  }

  public static final List<String> jvmArgs(
      Class<? extends TestSecurityManager> securityManagerClass) {
    return Collections.singletonList("-Djava.security.manager=" + securityManagerClass.getName());
  }

  public static final List<String> mainArgs() {
    return Collections.emptyList();
  }

  public static final Map<String, String> envVars() {
    return Collections.emptyMap();
  }
}
