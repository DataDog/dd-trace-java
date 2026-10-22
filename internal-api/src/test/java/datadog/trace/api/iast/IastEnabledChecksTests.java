package datadog.trace.api.iast;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE;
import static datadog.trace.api.config.IastConfig.IAST_EXPERIMENTAL_PROPAGATION_ENABLED;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class IastEnabledChecksTests {

  @ParameterizedTest(name = "{0}")
  @MethodSource("testMinJavaVersionArguments")
  void testMinJavaVersion(String majorJavaVersion, boolean expected) {
    boolean enabled = IastEnabledChecks.isMajorJavaVersionAtLeast(majorJavaVersion);

    assertEquals(expected, enabled);
  }

  static Stream<Arguments> testMinJavaVersionArguments() {
    return Stream.of(
        arguments("8", isJavaVersionAtLeast(8)),
        arguments("9", isJavaVersionAtLeast(9)),
        arguments("17", isJavaVersionAtLeast(17)),
        arguments("abcd", false));
  }

  @TableTest({
    "scenario | mode    | expected",
    "FULL     | FULL    | true    ",
    "DEFAULT  | DEFAULT | false   "
  })
  void testFullDetection(IastDetectionMode mode, boolean expected) {
    injectSysConfig(IAST_DETECTION_MODE, mode.name());

    boolean isFull = IastEnabledChecks.isFullDetection();

    assertEquals(expected, isFull);
  }

  @TableTest({
    "scenario | value | expected",
    "enabled  | true  | true    ",
    "disabled | false | false   "
  })
  void testExperimentalPropagation(String value, boolean expected) {
    injectSysConfig(IAST_EXPERIMENTAL_PROPAGATION_ENABLED, value);

    boolean isExperimentalPropagationEnabled = IastEnabledChecks.isExperimentalPropagationEnabled();

    assertEquals(expected, isExperimentalPropagationEnabled);
  }
}
