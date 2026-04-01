package datadog.crashtracking.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class RedactUtilsTest {

  @TableTest({
    "scenario             | input                            | expected                           ",
    "unknown package      | com/company/SomeType             | redacted/redacted/SomeType         ",
    "three-level package  | com/company/pkg/SomeType         | redacted/redacted/redacted/SomeType",
    "java prefix          | java/lang/String                 | java/lang/String                   ",
    "jdk prefix           | jdk/internal/misc/Unsafe         | jdk/internal/misc/Unsafe           ",
    "sun prefix           | sun/reflect/Reflection           | sun/reflect/Reflection             ",
    "javax prefix         | javax/net/ssl/SSLSocket          | javax/net/ssl/SSLSocket            ",
    "jakarta prefix       | jakarta/servlet/http/HttpServlet | jakarta/servlet/http/HttpServlet   ",
    "com/sun prefix       | com/sun/proxy/ProxyBuilder       | com/sun/proxy/ProxyBuilder         ",
    "com/oracle prefix    | com/oracle/jrockit/SomeClass     | com/oracle/jrockit/SomeClass       ",
    "datadog prefix       | datadog/trace/api/Tracer         | datadog/trace/api/Tracer           ",
    "com/datadog prefix   | com/datadog/agent/SomeClass      | com/datadog/agent/SomeClass        ",
    "com/datadoghq prefix | com/datadoghq/profiler/Profiler  | com/datadoghq/profiler/Profiler    ",
    "org/datadog prefix   | org/datadog/jmxfetch/App         | org/datadog/jmxfetch/App           ",
    "com/dd prefix        | com/dd/logs/LogService           | com/dd/logs/LogService             ",
    "no package           | SomeType                         | SomeType                           ",
    "inner class          | com/company/Outer$Inner          | redacted/redacted/Outer$Inner      "
  })
  void testRedactJvmClassName(String input, String expected) {
    assertThat(RedactUtils.redactJvmClassName(input)).isEqualTo(expected);
  }

  @TableTest({
    "scenario             | input                            | expected                           ",
    "unknown package      | com.company.SomeType             | redacted.redacted.SomeType         ",
    "three-level package  | com.company.pkg.SomeType         | redacted.redacted.redacted.SomeType",
    "java prefix          | java.lang.String                 | java.lang.String                   ",
    "jdk prefix           | jdk.internal.misc.Unsafe         | jdk.internal.misc.Unsafe           ",
    "sun prefix           | sun.reflect.Reflection           | sun.reflect.Reflection             ",
    "jakarta prefix       | jakarta.servlet.http.HttpServlet | jakarta.servlet.http.HttpServlet   ",
    "com.oracle prefix    | com.oracle.jrockit.SomeClass     | com.oracle.jrockit.SomeClass       ",
    "datadog prefix       | datadog.trace.api.Tracer         | datadog.trace.api.Tracer           ",
    "com.datadog prefix   | com.datadog.agent.SomeClass      | com.datadog.agent.SomeClass        ",
    "com.datadoghq prefix | com.datadoghq.profiler.Profiler  | com.datadoghq.profiler.Profiler    ",
    "org.datadog prefix   | org.datadog.jmxfetch.App         | org.datadog.jmxfetch.App           ",
    "com.dd prefix        | com.dd.logs.LogService           | com.dd.logs.LogService             ",
    "no package           | SomeType                         | SomeType                           ",
    "inner class          | com.company.Outer$Inner          | redacted.redacted.Outer$Inner      "
  })
  void testRedactDottedClassName(String input, String expected) {
    assertThat(RedactUtils.redactDottedClassName(input)).isEqualTo(expected);
  }

  @TableTest({
    "scenario       | input                                     | expected                                             ",
    "four segments  | /path/to/dir/lib.so                       | /redacted/redacted/dir/lib.so                        ",
    "two segments   | /dir/lib.so                               | /dir/lib.so                                          ",
    "three segments | /one/dir/lib.so                           | /redacted/dir/lib.so                                 ",
    "five segments  | /usr/lib/jvm/corretto-21/libjvm.so        | /redacted/redacted/redacted/corretto-21/libjvm.so    ",
    "six segments   | /usr/lib/jvm/corretto-21/server/libjvm.so | /redacted/redacted/redacted/redacted/server/libjvm.so"
  })
  void testRedactPath(String input, String expected) {
    assertThat(RedactUtils.redactPath(input)).isEqualTo(expected);
  }

  @Test
  void testRedactStringContent_redactsValue() {
    assertThat(RedactUtils.redactStringTypeValue(" - string: \"SourceFile\""))
        .isEqualTo(" - string: \"REDACTED\"");
  }

  @Test
  void testRedactStringContent_redactsSensitiveValue() {
    assertThat(
            RedactUtils.redactStringTypeValue(
                " - string: \"jdbc:postgresql://host/db?password=s3cr3t\""))
        .isEqualTo(" - string: \"REDACTED\"");
  }

  @Test
  void testRedactStringContent_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactStringTypeValue(" - klass: 'java/lang/Class'"))
        .isEqualTo(" - klass: 'java/lang/Class'");
  }

  @Test
  void testRedactTypeDescriptors_redactsUnknownPackage() {
    assertThat(RedactUtils.redactTypeDescriptors("'(Lcom/company/Type;ILjava/lang/String;)V'"))
        .isEqualTo("'(Lredacted/redacted/Type;ILjava/lang/String;)V'");
  }

  @Test
  void testRedactTypeDescriptors_keepsKnownPackages() {
    assertThat(RedactUtils.redactTypeDescriptors("'(Ljava/util/List;Ljdk/internal/misc/Unsafe;)V'"))
        .isEqualTo("'(Ljava/util/List;Ljdk/internal/misc/Unsafe;)V'");
  }

  @Test
  void testRedactKlassReference_redactsUnknownPackage() {
    assertThat(RedactUtils.redactKlassReference("{0x...} - klass: 'com/company/MyClass'"))
        .isEqualTo("{0x...} - klass: 'redacted/redacted/MyClass'");
  }

  @Test
  void testRedactKlassReference_keepsKnownPackage() {
    assertThat(RedactUtils.redactKlassReference("{0x...} - klass: 'java/lang/Class'"))
        .isEqualTo("{0x...} - klass: 'java/lang/Class'");
  }

  @Test
  void testRedactMethodClass_redactsUnknownPackage() {
    assertThat(
            RedactUtils.redactMethodClass(
                "{method} {0x...} 'doWork' '(I)V' in 'com/company/Worker'"))
        .isEqualTo("{method} {0x...} 'doWork' '(I)V' in 'redacted/redacted/Worker'");
  }

  @Test
  void testRedactMethodClass_keepsKnownPackage() {
    assertThat(
            RedactUtils.redactMethodClass(
                "{method} {0x...} 'getLong' '(J)J' in 'jdk/internal/misc/Unsafe'"))
        .isEqualTo("{method} {0x...} 'getLong' '(J)J' in 'jdk/internal/misc/Unsafe'");
  }

  @Test
  void testRedactLibraryPath_redactsIntermediateSegments() {
    assertThat(
            RedactUtils.redactLibraryPath(
                "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /opt/company/lib/server/app.so at 0x0000ffff9e1a0000"))
        .isEqualTo(
            "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /redacted/redacted/redacted/server/app.so at 0x0000ffff9e1a0000");
  }

  @Test
  void testRedactLibraryPath_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactLibraryPath("0x00007f37a16e2590 is an unknown value"))
        .isEqualTo("0x00007f37a16e2590 is an unknown value");
  }

  @Test
  void testRedactDottedClassOopRef_redactsUnknownPackage() {
    assertThat(
            RedactUtils.redactDottedClassOopRef(
                " - private transient 'name' 'Ljava/lang/String;' @44  \"com.company.SomeType\"{0x00000007142f7200} (0xe285ee40)"))
        .isEqualTo(
            " - private transient 'name' 'Ljava/lang/String;' @44  \"redacted.redacted.SomeType\"{0x00000007142f7200} (0xe285ee40)");
  }

  @Test
  void testRedactDottedClassOopRef_keepsKnownPackage() {
    assertThat(
            RedactUtils.redactDottedClassOopRef(
                " - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"{0x00000007142f7200} (0xe285ee40)"))
        .isEqualTo(
            " - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"{0x00000007142f7200} (0xe285ee40)");
  }

  @Test
  void testRedactOopClassName_redactsUnknownPackage() {
    assertThat(
            RedactUtils.redactOopClassName("0x00000007ffe85850 is an oop: com.company.UserData "))
        .isEqualTo("0x00000007ffe85850 is an oop: redacted.redacted.UserData ");
  }

  @Test
  void testRedactOopClassName_keepsKnownPackage() {
    assertThat(RedactUtils.redactOopClassName("0x00000007ffe85850 is an oop: java.lang.Class "))
        .isEqualTo("0x00000007ffe85850 is an oop: java.lang.Class ");
  }

  @Test
  void testRedactRegisterToMemoryMapping_methodDescriptor() {
    String value =
        "{method} {0x00007f3639c2ff00} 'saveJob' '(Lcom/company/Job;ILjava/lang/String;)V' in 'com/company/JobService'";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "{method} {0x00007f3639c2ff00} 'saveJob' '(Lredacted/redacted/Job;ILjava/lang/String;)V' in 'redacted/redacted/JobService'");
  }

  @Test
  void testRedactRegisterToMemoryMapping_multilineOopDump() {
    // Mirrors the java.lang.Class oop dump format: the 'name' field holds a dotted class name
    // as an inline string value followed by an OOP ref, and 'loader' holds a typed object ref.
    String value =
        "0x00000007ffe85850 is an oop: com.company.Config \n"
            + "{0x00000007ffe85850} - klass: 'com/company/Config'\n"
            + " - ---- fields (total size 3 words):\n"
            + " - private transient 'name' 'Ljava/lang/String;' @12  \"com.company.Config\"{0x00000007aabbccdd} (0x12345678)\n"
            + " - private 'owner' 'Lcom/company/User;' @16  null (0x00000000)\n"
            + " - string: \"some sensitive value\"";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "0x00000007ffe85850 is an oop: redacted.redacted.Config \n"
                + "{0x00000007ffe85850} - klass: 'redacted/redacted/Config'\n"
                + " - ---- fields (total size 3 words):\n"
                + " - private transient 'name' 'Ljava/lang/String;' @12  \"redacted.redacted.Config\"{0x00000007aabbccdd} (0x12345678)\n"
                + " - private 'owner' 'Lredacted/redacted/User;' @16  null (0x00000000)\n"
                + " - string: \"REDACTED\"");
  }

  @Test
  void testRedactRegisterToMemoryMapping_libraryPath() {
    assertThat(
            RedactUtils.redactRegisterToMemoryMapping(
                "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /usr/lib/jvm/corretto-21/server/libjvm.so at 0x0000ffff9e1a0000"))
        .isEqualTo(
            "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /redacted/redacted/redacted/redacted/server/libjvm.so at 0x0000ffff9e1a0000");
  }

  @Test
  void testRedactRegisterToMemoryMapping_safeValuesUnchanged() {
    assertThat(
            RedactUtils.redactRegisterToMemoryMapping(
                "0x00007f35e6253190 is pointing into the stack for thread: 0x00007f36cd96cc80"))
        .isEqualTo("0x00007f35e6253190 is pointing into the stack for thread: 0x00007f36cd96cc80");
    assertThat(RedactUtils.redactRegisterToMemoryMapping("0x0 is NULL")).isEqualTo("0x0 is NULL");
    assertThat(RedactUtils.redactRegisterToMemoryMapping("0x000000008fd66048 is an unknown value"))
        .isEqualTo("0x000000008fd66048 is an unknown value");
  }
}
