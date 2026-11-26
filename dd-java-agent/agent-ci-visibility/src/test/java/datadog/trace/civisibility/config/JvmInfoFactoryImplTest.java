package datadog.trace.civisibility.config;

import static datadog.trace.api.config.CiVisibilityConfig.TEST_OPTIMIZATION_RUNTIME_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import datadog.environment.SystemProperties;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import datadog.trace.util.ProcessUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class JvmInfoFactoryImplTest {

  @Test
  void appliesRuntimeTagOverridesToForkedJvmInfo() {
    JvmInfo currentJvm = JvmInfo.CURRENT_JVM;
    WithConfigExtension.injectSysConfig(
        TEST_OPTIMIZATION_RUNTIME_TAGS,
        "{\"runtime.version\":\"11.0.0\",\"runtime.vendor\":\"Pinned Vendor\"}");

    JvmInfo jvmInfo = JvmInfoFactoryImpl.doGetJvmInfo(currentJvmExecutable());

    assertNotSame(currentJvm, jvmInfo);
    assertEquals(SystemProperties.get("java.runtime.name"), jvmInfo.getName());
    assertEquals("11.0.0", jvmInfo.getVersion());
    assertEquals("Pinned Vendor", jvmInfo.getVendor());
  }

  private static Path currentJvmExecutable() {
    Path currentJvmPath = Paths.get(ProcessUtils.getCurrentJvmPath());
    if (Files.isRegularFile(currentJvmPath) && Files.isExecutable(currentJvmPath)) {
      return currentJvmPath;
    }
    if (!currentJvmPath.endsWith("bin")) {
      currentJvmPath = currentJvmPath.resolve("bin");
    }
    return currentJvmPath.resolve("java");
  }
}
