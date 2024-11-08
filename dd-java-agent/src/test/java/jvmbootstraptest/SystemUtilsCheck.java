package jvmbootstraptest;

import datadog.trace.agent.test.IntegrationTestUtils;
import datadog.trace.api.Config;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class SystemUtilsCheck {
  public static void main(String[] args) {
    Config config = Config.get();
    System.out.println(config.toString());
    if (!Config.get().getEnv().equals("unnamed-java-app")) {
      System.out.println("Env is not set to default value."); // something else here?
      System.exit(0);
    }
    System.exit(2);
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
    Map<String, String> map = new HashMap<String, String>();
    map.put("DD_SERVICE", "test-service");
    return map;
  }
}
