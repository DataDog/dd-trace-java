package jvmbootstraptest;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.File;
import java.io.FilePermission;
import java.util.HashMap;
import java.util.Map;

/**
 * Basic sanity check that InitializationTelemetry is functioning
 *
 * <p>Checks edge cases where InitializationTelemetry is blocked by SecurityManagers
 */
public class InitializationTelemetryCheck {
  public static void main(String[] args) {}
  
  /** Blocks the loading of the agent bootstrap */
  public static class BlockAgentLoading extends TestSecurityManager {
    @Override
    protected boolean checkFileReadPermission(FilePermission perm, Object ctx, String filePath) {
      // NOTE: Blocking classes doesn't have the desired effect
      if (filePath.endsWith(".jar") && filePath.contains("dd-java-agent")) return false;

      return super.checkFileReadPermission(perm, ctx, filePath);
    }
  }

  /** Intended to break agent initialization */
  public static class BlockByteBuddy extends TestSecurityManager {
    @Override
    protected boolean checkOtherRuntimePermission(
        RuntimePermission perm, Object ctx, String permName) {
      switch (permName) {
        case "net.bytebuddy.createJavaDispatcher":
          return false;

        default:
          return super.checkOtherRuntimePermission(perm, ctx, permName);
      }
    }
  }

  public static class BlockForwarderEnvVar extends TestSecurityManager {
    @Override
    protected boolean checkRuntimeEnvironmentAccess(
        RuntimePermission perm, Object ctx, String envVar) {
      switch (envVar) {
        case "DD_TELEMETRY_FORWARDER_PATH":
          return false;

        default:
          return super.checkRuntimeEnvironmentAccess(perm, ctx, envVar);
      }
    }
  }
  
  public static class BlockForwarderExecution extends TestSecurityManager {
	@Override
	protected boolean checkFileExecutePermission(FilePermission perm, Object ctx, String filePath) {
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
          InitializationTelemetryCheck.class.getName(),
          InitializationTelemetryCheck.jvmArgs(securityManagerClass),
          InitializationTelemetryCheck.mainArgs(),
          InitializationTelemetryCheck.envVars(),
          jarFile,
          printStreams);
    } finally {
      jarFile.delete();
    }
  }

  public static final Class<?>[] requiredClasses(
      Class<? extends TestSecurityManager> securityManagerClass) {
	
	if ( securityManagerClass == null ) {
	  return new Class<?>[] { InitializationTelemetryCheck.class };
	} else {
      return new Class<?>[] {
        InitializationTelemetryCheck.class,
        securityManagerClass,
        TestSecurityManager.class,
        CustomSecurityManager.class
      };
	}
  }

  public static final String[] jvmArgs(Class<? extends TestSecurityManager> securityManagerClass) {
    if ( securityManagerClass == null ) {
      return new String[] {};
    } else {
	  return new String[] {"-Djava.security.manager=" + securityManagerClass.getName()};
    }
  }

  public static final String[] mainArgs() {
    return new String[] {};
  }

  public static final Map<String, String> envVars() {
    Map<String, String> envVars = new HashMap<>();
    envVars.put("DD_TELEMETRY_FORWARDER_PATH", "/dummy/path");
    return envVars;
  }
}
