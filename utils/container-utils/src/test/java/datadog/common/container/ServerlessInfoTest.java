package datadog.common.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.ControllableEnvironmentVariables;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.File;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class ServerlessInfoTest extends DDJavaSpecification {

  // AWS_LAMBDA_FUNCTION_NAME is ServerlessInfo.AWS_FUNCTION_VARIABLE (private constant)
  private static final String AWS_FUNCTION_VARIABLE = "AWS_LAMBDA_FUNCTION_NAME";

  private static final ControllableEnvironmentVariables environmentVariables =
      ControllableEnvironmentVariables.setup();

  @AfterEach
  void clearEnvVars() {
    environmentVariables.clear();
  }

  @TableTest({
    "functionName | serverlessEnv",
    "             | false        ",
    "''           | false        ",
    "someName     | true         "
  })
  void testServerlessDetection(String functionName, boolean serverlessEnv) {
    environmentVariables.set(AWS_FUNCTION_VARIABLE, functionName);

    ServerlessInfo info = new ServerlessInfo();

    assertEquals(serverlessEnv, info.isRunningInServerlessEnvironment());
    assertEquals(functionName, info.getFunctionName());
  }

  @Test
  void testServerlessHasExtensionFalse() {
    ServerlessInfo info = new ServerlessInfo();

    assertFalse(info.hasExtension());
  }

  @Test
  void testServerlessHasExtensionFalseSinceExtensionPathIsNull() throws Exception {
    // ServerlessInfo(String extensionPath) is private — access via reflection
    Constructor<ServerlessInfo> constructor =
        ServerlessInfo.class.getDeclaredConstructor(String.class);
    constructor.setAccessible(true);
    ServerlessInfo info = constructor.newInstance((Object) null);

    assertFalse(info.hasExtension());
  }

  @Test
  void testServerlessHasExtensionTrue() throws Exception {
    File f = File.createTempFile("fake-", "extension");
    f.deleteOnExit();

    Constructor<ServerlessInfo> constructor =
        ServerlessInfo.class.getDeclaredConstructor(String.class);
    constructor.setAccessible(true);
    ServerlessInfo info = constructor.newInstance(f.getAbsolutePath());

    assertTrue(info.hasExtension());
  }
}
