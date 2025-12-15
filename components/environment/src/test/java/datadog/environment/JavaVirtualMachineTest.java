package datadog.environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.api.condition.JRE.JAVA_17;
import static org.junit.jupiter.api.condition.JRE.JAVA_21;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;
import static org.junit.jupiter.api.condition.JRE.JAVA_9;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaVirtualMachineTest {
  @Test
  @EnabledOnJre(JAVA_8)
  void onJava8Only() {
    assertTrue(JavaVirtualMachine.isJavaVersion(8));
    assertFalse(JavaVirtualMachine.isJavaVersion(11));
  }

  @Test
  @EnabledOnJre(JAVA_11)
  void onJava11Only() {
    assertFalse(JavaVirtualMachine.isJavaVersion(8));
    assertTrue(JavaVirtualMachine.isJavaVersion(11));
    assertFalse(JavaVirtualMachine.isJavaVersion(17));
  }

  @Test
  @EnabledOnJre(JAVA_17)
  void onJava17Only() {
    assertFalse(JavaVirtualMachine.isJavaVersion(11));
    assertTrue(JavaVirtualMachine.isJavaVersion(17));
    assertFalse(JavaVirtualMachine.isJavaVersion(21));
  }

  @Test
  @EnabledOnJre(JAVA_21)
  void onJava21Only() {
    assertFalse(JavaVirtualMachine.isJavaVersion(17));
    assertTrue(JavaVirtualMachine.isJavaVersion(21));
    assertFalse(JavaVirtualMachine.isJavaVersion(25));
  }

  @Test
  void onJava7andHigher() {
    assertTrue(JavaVirtualMachine.isJavaVersionAtLeast(7));
  }

  @Test
  void onJava8AndHigher() {
    for (int version = 7; version <= 8; version++) {
      assertTrue(JavaVirtualMachine.isJavaVersionAtLeast(version));
    }
  }

  @Test
  @EnabledForJreRange(min = JAVA_11)
  void onJava11AndHigher() {
    for (int version = 7; version <= 11; version++) {
      assertTrue(JavaVirtualMachine.isJavaVersionAtLeast(version));
    }
  }

  @Test
  @EnabledForJreRange(min = JAVA_17)
  void onJava17AndHigher() {
    for (int version = 7; version <= 17; version++) {
      assertTrue(JavaVirtualMachine.isJavaVersionAtLeast(version));
    }
  }

  @Test
  @EnabledForJreRange(min = JAVA_21)
  void onJava21AndHigher() {
    for (int version = 7; version <= 21; version++) {
      assertTrue(JavaVirtualMachine.isJavaVersionAtLeast(version));
    }
  }

  @Test
  @EnabledForJreRange(min = JAVA_8, max = JAVA_9)
  void fromJava8to9() {
    assertFalse(JavaVirtualMachine.isJavaVersionBetween(7, 8));
    assertTrue(JavaVirtualMachine.isJavaVersionBetween(8, 10));
    assertFalse(JavaVirtualMachine.isJavaVersionBetween(10, 11));
  }

  @Test
  @EnabledForJreRange(min = JAVA_11, max = JAVA_11)
  void fromJava11to17() {
    assertFalse(JavaVirtualMachine.isJavaVersionBetween(8, 11));
    assertTrue(JavaVirtualMachine.isJavaVersionBetween(11, 18));
    assertFalse(JavaVirtualMachine.isJavaVersionBetween(18, 21));
  }

  @Test
  @EnabledIfSystemProperty(named = "java.vendor.version", matches = ".*graalvm.*")
  void onlyOnGraalVm() {
    assertTrue(JavaVirtualMachine.isGraalVM());
    assertFalse(JavaVirtualMachine.isIbm8());
    assertFalse(JavaVirtualMachine.isJ9());
    assertFalse(JavaVirtualMachine.isOracleJDK8());
  }

  @Test
  @EnabledIfSystemProperty(named = "java.vm.vendor", matches = ".*IBM.*")
  @EnabledOnJre(JAVA_8)
  void onlyOnIbm8() {
    assertFalse(JavaVirtualMachine.isGraalVM());
    assertTrue(JavaVirtualMachine.isIbm());
    assertTrue(JavaVirtualMachine.isIbm8());
    assertTrue(JavaVirtualMachine.isJ9());
    assertFalse(JavaVirtualMachine.isOracleJDK8());
  }

  @Test
  @EnabledIfSystemProperty(named = "java.vm.name", matches = ".*J9.*")
  void onlyOnJ9() {
    assertFalse(JavaVirtualMachine.isGraalVM());
    assertTrue(JavaVirtualMachine.isJ9());
    assertFalse(JavaVirtualMachine.isOracleJDK8());
  }

  @Test
  @EnabledIfSystemProperty(named = "java.vm.vendor", matches = ".*Oracle.*")
  @DisabledIfSystemProperty(named = "java.runtime.name", matches = ".*OpenJDK.*")
  @EnabledOnJre(JAVA_8)
  void onlyOnOracleJDK8() {
    assertFalse(JavaVirtualMachine.isGraalVM());
    assertFalse(JavaVirtualMachine.isIbm8());
    assertFalse(JavaVirtualMachine.isJ9());
    assertTrue(JavaVirtualMachine.isOracleJDK8());
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "1.8.0_265 | 1.8.0_265-b01 | OpenJDK        | AdoptOpenJDK | 1.8.0_265 | b01 | OpenJDK        | AdoptOpenJDK",
        "1.8.0_265 | 1.8-b01       | OpenJDK        | AdoptOpenJDK | 1.8.0_265 | ''  | OpenJDK        | AdoptOpenJDK",
        "19        | 19            | OpenJDK 64-Bit | Homebrew     | 19        | ''  | OpenJDK 64-Bit | Homebrew",
        "17        | null          | null           | null         | 17        | ''  | ''             | ''",
        "null      | 17            | null           | null         | ''        | ''  | ''             | ''",
      },
      nullValues = "null",
      delimiter = '|')
  void testRuntimeParsing(
      String javaVersion,
      String javaRuntimeVersion,
      String javaRuntimeName,
      String javaVmVendor,
      String expectedVersion,
      String expectedPatches,
      String expectedName,
      String expectedVendor) {
    JavaVirtualMachine.Runtime runtime =
        new JavaVirtualMachine.Runtime(
            javaVersion, javaRuntimeVersion, javaRuntimeName, javaVmVendor, null);
    assertEquals(expectedVersion, runtime.version);
    assertEquals(expectedPatches, runtime.patches);
    assertEquals(expectedName, runtime.name);
    assertEquals(expectedVendor, runtime.vendor);
  }
}
