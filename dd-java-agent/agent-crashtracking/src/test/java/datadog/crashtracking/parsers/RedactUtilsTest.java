package datadog.crashtracking.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class RedactUtilsTest {

  @TableTest({
    "scenario             | input                            | expected                        ",
    "unknown package      | com/company/SomeType             | redacted/Redacted               ",
    "three-level package  | com/company/pkg/SomeType         | redacted/Redacted               ",
    "java prefix          | java/lang/String                 | java/lang/String                ",
    "jdk prefix           | jdk/internal/misc/Unsafe         | jdk/internal/misc/Unsafe        ",
    "sun prefix           | sun/reflect/Reflection           | sun/reflect/Reflection          ",
    "javax prefix         | javax/net/ssl/SSLSocket          | javax/net/ssl/SSLSocket         ",
    "jakarta prefix       | jakarta/servlet/http/HttpServlet | jakarta/servlet/http/HttpServlet",
    "com/sun prefix       | com/sun/proxy/ProxyBuilder       | com/sun/proxy/ProxyBuilder      ",
    "com/oracle prefix    | com/oracle/jrockit/SomeClass     | com/oracle/jrockit/SomeClass    ",
    "datadog prefix       | datadog/trace/api/Tracer         | datadog/trace/api/Tracer        ",
    "com/datadog prefix   | com/datadog/agent/SomeClass      | com/datadog/agent/SomeClass     ",
    "com/datadoghq prefix | com/datadoghq/profiler/Profiler  | com/datadoghq/profiler/Profiler ",
    "org/datadog prefix   | org/datadog/jmxfetch/App         | org/datadog/jmxfetch/App        ",
    "com/dd prefix        | com/dd/logs/LogService           | com/dd/logs/LogService          ",
    "no package           | SomeType                         | SomeType                        ",
    "inner class          | com/company/Outer$Inner          | redacted/Redacted               "
  })
  void testRedactJvmClassName(String input, String expected) {
    assertThat(RedactUtils.redactJvmClassName(input)).isEqualTo(expected);
  }

  @TableTest({
    "scenario             | input                            | expected                        ",
    "unknown package      | com.company.SomeType             | redacted.Redacted               ",
    "three-level package  | com.company.pkg.SomeType         | redacted.Redacted               ",
    "java prefix          | java.lang.String                 | java.lang.String                ",
    "jdk prefix           | jdk.internal.misc.Unsafe         | jdk.internal.misc.Unsafe        ",
    "sun prefix           | sun.reflect.Reflection           | sun.reflect.Reflection          ",
    "jakarta prefix       | jakarta.servlet.http.HttpServlet | jakarta.servlet.http.HttpServlet",
    "com.oracle prefix    | com.oracle.jrockit.SomeClass     | com.oracle.jrockit.SomeClass    ",
    "datadog prefix       | datadog.trace.api.Tracer         | datadog.trace.api.Tracer        ",
    "com.datadog prefix   | com.datadog.agent.SomeClass      | com.datadog.agent.SomeClass     ",
    "com.datadoghq prefix | com.datadoghq.profiler.Profiler  | com.datadoghq.profiler.Profiler ",
    "org.datadog prefix   | org.datadog.jmxfetch.App         | org.datadog.jmxfetch.App        ",
    "com.dd prefix        | com.dd.logs.LogService           | com.dd.logs.LogService          ",
    "no package           | SomeType                         | SomeType                        ",
    "inner class          | com.company.Outer$Inner          | redacted.Redacted               "
  })
  void testRedactDottedClassName(String input, String expected) {
    assertThat(RedactUtils.redactDottedClassName(input)).isEqualTo(expected);
  }

  @TableTest({
    "scenario       | input                                     | expected                       ",
    "four segments  | /path/to/dir/lib.so                       | /redacted/dir/lib.so           ",
    "two segments   | /dir/lib.so                               | /dir/lib.so                    ",
    "three segments | /one/dir/lib.so                           | /redacted/dir/lib.so           ",
    "five segments  | /usr/lib/jvm/corretto-21/libjvm.so        | /redacted/corretto-21/libjvm.so",
    "six segments   | /usr/lib/jvm/corretto-21/server/libjvm.so | /redacted/server/libjvm.so     "
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
        .isEqualTo("'(Lredacted/Redacted;ILjava/lang/String;)V'");
  }

  @Test
  void testRedactTypeDescriptors_keepsKnownPackages() {
    assertThat(RedactUtils.redactTypeDescriptors("'(Ljava/util/List;Ljdk/internal/misc/Unsafe;)V'"))
        .isEqualTo("'(Ljava/util/List;Ljdk/internal/misc/Unsafe;)V'");
  }

  @Test
  void testRedactKlassReference_redactsUnknownPackage() {
    assertThat(RedactUtils.redactKlassReference("{0x...} - klass: 'com/company/MyClass'"))
        .isEqualTo("{0x...} - klass: 'redacted/Redacted'");
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
        .isEqualTo("{method} {0x...} 'doWork' '(I)V' in 'redacted/Redacted'");
  }

  @Test
  void testRedactMethodClass_keepsKnownPackage() {
    assertThat(
            RedactUtils.redactMethodClass(
                "{method} {0x...} 'getLong' '(J)J' in 'jdk/internal/misc/Unsafe'"))
        .isEqualTo("{method} {0x...} 'getLong' '(J)J' in 'jdk/internal/misc/Unsafe'");
  }

  @Test
  void testRedactLibraryPath_offsetFormat() {
    assertThat(
            RedactUtils.redactLibraryPath(
                "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /opt/company/lib/server/app.so at 0x0000ffff9e1a0000"))
        .isEqualTo(
            "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /redacted/server/app.so at 0x0000ffff9e1a0000");
  }

  @Test
  void testRedactLibraryPath_symbolOffsetFormat() {
    // macOS/Linux: dladdr resolved a C++ mangled symbol — "symbol+offset in /path at 0x..."
    assertThat(
            RedactUtils.redactLibraryPath(
                "0x0000000106c1ccc0: _ZN19TemplateInterpreter13_active_tableE+0 in /Users/USER/.local/share/mise/installs/java/25.0.2/lib/server/libjvm.dylib at 0x0000000105efc000"))
        .isEqualTo(
            "0x0000000106c1ccc0: _ZN19TemplateInterpreter13_active_tableE+0 in /redacted/server/libjvm.dylib at 0x0000000105efc000");
  }

  @Test
  void testRedactLibraryPath_cSymbolFormat() {
    // macOS: C symbol "symbol+0 in /usr/lib/system/lib.dylib at 0x..."
    assertThat(
            RedactUtils.redactLibraryPath(
                "0x0000000182d709d0: pthread_jit_write_protect_np+0 in /usr/lib/system/libsystem_pthread.dylib at 0x0000000182d69000"))
        .isEqualTo(
            "0x0000000182d709d0: pthread_jit_write_protect_np+0 in /redacted/system/libsystem_pthread.dylib at 0x0000000182d69000");
  }

  @Test
  void testRedactLibraryPath_doesNotMatchInterpreterCodelet() {
    // "code_begin+1776 in an Interpreter codelet" — "an" doesn't start with "/" so no match
    assertThat(
            RedactUtils.redactLibraryPath(
                "0x0000000116d0c970 is at code_begin+1776 in an Interpreter codelet"))
        .isEqualTo("0x0000000116d0c970 is at code_begin+1776 in an Interpreter codelet");
  }

  @Test
  void testRedactLibraryPath_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactLibraryPath("0x00007f37a16e2590 is an unknown value"))
        .isEqualTo("0x00007f37a16e2590 is an unknown value");
  }

  @Test
  void testRedactDottedClassOopRef_redactsAnyStringOopRef() {
    // Without oop-type context, all "value"{0x...} OOP refs are treated as arbitrary application
    // data and fully redacted — even if the value looks like a class name
    assertThat(
            RedactUtils.redactDottedClassOopRef(
                " - private transient 'name' 'Ljava/lang/String;' @44  \"com.company.SomeType\"{0x00000007142f7200} (0xe285ee40)"))
        .isEqualTo(
            " - private transient 'name' 'Ljava/lang/String;' @44  \"REDACTED\"{0x00000007142f7200} (0xe285ee40)");
    // Dotted names with known packages are also fully redacted — any string can be a secret
    assertThat(
            RedactUtils.redactDottedClassOopRef(
                " - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"{0x00000007142f7200} (0xe285ee40)"))
        .isEqualTo(
            " - private transient 'name' 'Ljava/lang/String;' @44  \"REDACTED\"{0x00000007142f7200} (0xe285ee40)");
    // Plain single-word strings (no dots) are also redacted
    assertThat(
            RedactUtils.redactDottedClassOopRef(
                " - final 'value' 'Ljava/lang/String;' @40  \"SourceFile\"{0x00000007ffe7a6a0} (0xfffcf4d4)"))
        .isEqualTo(
            " - final 'value' 'Ljava/lang/String;' @40  \"REDACTED\"{0x00000007ffe7a6a0} (0xfffcf4d4)");
  }

  @Test
  void testRedactOopClassName_redactsUnknownPackage() {
    assertThat(
            RedactUtils.redactOopClassName("0x00000007ffe85850 is an oop: com.company.UserData "))
        .isEqualTo("0x00000007ffe85850 is an oop: redacted.Redacted ");
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
            "{method} {0x00007f3639c2ff00} 'saveJob' '(Lredacted/Redacted;ILjava/lang/String;)V' in 'redacted/Redacted'");
  }

  @Test
  void testRedactRegisterToMemoryMapping_multilineOopDump() {
    // Non-java.lang.Class oop: ALL "value"{0x...} OOP refs are fully redacted to "REDACTED"
    // regardless of their shape — any string value may be a secret.
    String value =
        "0x00000007142f8848 is an oop: com.company.SymbolEntry \n"
            + "{0x00000007142f8848} - klass: 'com/company/SymbolEntry'\n"
            + " - ---- fields (total size 9 words):\n"
            + " - final 'tag' 'Ljava/lang/String;' @12  \"SourceFile\"{0x00000007ffe7a6a0} (0xfffcf4d4)\n"
            + " - final 'value' 'Ljava/lang/String;' @16  \"com.company.Config\"{0x00000007aabbccdd} (0x12345678)\n"
            + " - final 'hint' 'Ljava/lang/String;' @20  \"java.vendor.url.bug\"{0x00000007aabbccee} (0x12345679)\n"
            + " - final 'owner' 'Ljava/lang/String;' @24  null (0x00000000)\n"
            + " - string: \"some sensitive value\"";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "0x00000007142f8848 is an oop: redacted.Redacted \n"
                + "{0x00000007142f8848} - klass: 'redacted/Redacted'\n"
                + " - ---- fields (total size 9 words):\n"
                + " - final 'tag' 'Ljava/lang/String;' @12  \"REDACTED\"{0x00000007ffe7a6a0} (0xfffcf4d4)\n"
                + " - final 'value' 'Ljava/lang/String;' @16  \"REDACTED\"{0x00000007aabbccdd} (0x12345678)\n"
                + " - final 'hint' 'Ljava/lang/String;' @20  \"REDACTED\"{0x00000007aabbccee} (0x12345679)\n"
                + " - final 'owner' 'Ljava/lang/String;' @24  null (0x00000000)\n"
                + " - string: \"REDACTED\"");
  }

  @Test
  void testRedactRegisterToMemoryMapping_javaLangClassOopRedactsUnknownClasses() {
    // java.lang.Class oop: String OOP refs in field values are treated as class names.
    // Unknown-package classes are redacted to redacted.Redacted; known packages are preserved.
    String value =
        "0x00000007ffe85850 is an oop: java.lang.Class \n"
            + "{0x00000007ffe85850} - klass: 'java/lang/Class'\n"
            + " - ---- fields (total size 25 words):\n"
            + " - private transient 'name' 'Ljava/lang/String;' @44  \"com.company.Config\"{0x00000007aabbccdd} (0x12345678)\n"
            + " - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"{0x00000007142f7200} (0xe285ee40)";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "0x00000007ffe85850 is an oop: java.lang.Class \n"
                + "{0x00000007ffe85850} - klass: 'java/lang/Class'\n"
                + " - ---- fields (total size 25 words):\n"
                + " - private transient 'name' 'Ljava/lang/String;' @44  \"redacted.Redacted\"{0x00000007aabbccdd} (0x12345678)\n"
                + " - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"{0x00000007142f7200} (0xe285ee40)");
  }

  @Test
  void testRedactRegisterToMemoryMapping_libraryPath() {
    assertThat(
            RedactUtils.redactRegisterToMemoryMapping(
                "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /usr/lib/jvm/corretto-21/server/libjvm.so at 0x0000ffff9e1a0000"))
        .isEqualTo(
            "0x0000ffff9efa1650: <offset 0x0000000000e01650> in /redacted/server/libjvm.so at 0x0000ffff9e1a0000");
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

  @Test
  void testRedactReadableMemoryHexDump_withAddressAndPipe() {
    // Linux/macOS amd64: address + " | " + bytes — keep address, redact bytes
    assertThat(
            RedactUtils.redactReadableMemoryHexDump(
                "0x0000000100a17cb0 points into unknown readable memory: 0x00000000ffffffff | ff ff ff ff 00 00 00 00"))
        .isEqualTo(
            "0x0000000100a17cb0 points into unknown readable memory: 0x00000000ffffffff | REDACTED");
  }

  @Test
  void testRedactReadableMemoryHexDump_withoutAddress() {
    // Linux aarch64: bytes only — redact everything after the colon
    assertThat(
            RedactUtils.redactReadableMemoryHexDump(
                "0x0000ffff9f686ca4 points into unknown readable memory: 06 00 00 00"))
        .isEqualTo("0x0000ffff9f686ca4 points into unknown readable memory: REDACTED");
  }

  @Test
  void testRedactReadableMemoryHexDump_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactReadableMemoryHexDump("0x00007f37a16e2590 is an unknown value"))
        .isEqualTo("0x00007f37a16e2590 is an unknown value");
  }

  @Test
  void testRedactObjFieldRef_redactsUnknownPackage() {
    assertThat(
            RedactUtils.redactObjFieldRef(
                " - 'owner' 'Lcom/company/Owner;' @12  a 'com/company/Owner'{0x00007f3700001234} (0x12345678)"))
        .isEqualTo(
            " - 'owner' 'Lcom/company/Owner;' @12  a 'redacted/Redacted'{0x00007f3700001234} (0x12345678)");
  }

  @Test
  void testRedactObjFieldRef_keepsKnownPackage() {
    assertThat(
            RedactUtils.redactObjFieldRef(
                " - 'loader' 'Ljava/lang/ClassLoader;' @12  a 'java/lang/ClassLoader'{0x00007f3700001234} (0x12345678)"))
        .isEqualTo(
            " - 'loader' 'Ljava/lang/ClassLoader;' @12  a 'java/lang/ClassLoader'{0x00007f3700001234} (0x12345678)");
  }

  @Test
  void testRedactObjFieldRef_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactObjFieldRef("0x00007f37a16e2590 is an unknown value"))
        .isEqualTo("0x00007f37a16e2590 is an unknown value");
  }

  @Test
  void testRedactNmethodClass_dottedUnknownPackage() {
    assertThat(
            RedactUtils.redactNmethodClass(
                "Compiled method (c2) 3068 4       com.company.Foo::methodName (456 bytes)"))
        .isEqualTo("Compiled method (c2) 3068 4       redacted.Redacted::methodName (456 bytes)");
  }

  @Test
  void testRedactNmethodClass_dottedKnownPackage() {
    assertThat(
            RedactUtils.redactNmethodClass(
                "Compiled method (c2) 3068 4       java.util.HashMap::resize (456 bytes)"))
        .isEqualTo("Compiled method (c2) 3068 4       java.util.HashMap::resize (456 bytes)");
  }

  @Test
  void testRedactNmethodClass_slashUnknownPackage() {
    assertThat(RedactUtils.redactNmethodClass("com/company/Foo::methodName"))
        .isEqualTo("redacted/Redacted::methodName");
  }

  @Test
  void testRedactNmethodClass_slashKnownPackage() {
    assertThat(RedactUtils.redactNmethodClass("java/util/HashMap::resize"))
        .isEqualTo("java/util/HashMap::resize");
  }

  @Test
  void testRedactNmethodClass_leavesUnrelatedLinesUnchanged() {
    assertThat(RedactUtils.redactNmethodClass("0x00007f37a16e2590 is an unknown value"))
        .isEqualTo("0x00007f37a16e2590 is an unknown value");
  }

  @Test
  void testRedactRegisterToMemoryMapping_objFieldRef() {
    // Non-java.lang.Class oop with object-reference field: a 'ClassName' is redacted
    String value =
        "0x00000007142f8848 is an oop: com.company.Holder \n"
            + "{0x00000007142f8848} - klass: 'com/company/Holder'\n"
            + " - ---- fields (total size 3 words):\n"
            + " - 'ref' 'Ljava/lang/Object;' @12  a 'com/company/Inner'{0x00007f1200003456} (0xabcdef01)";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "0x00000007142f8848 is an oop: redacted.Redacted \n"
                + "{0x00000007142f8848} - klass: 'redacted/Redacted'\n"
                + " - ---- fields (total size 3 words):\n"
                + " - 'ref' 'Ljava/lang/Object;' @12  a 'redacted/Redacted'{0x00007f1200003456} (0xabcdef01)");
  }

  @Test
  void testRedactRegisterToMemoryMapping_nmethodCompiledMethod() {
    // nmethod entry (JDK 11+): "Compiled method" line class name is redacted
    String value =
        "0x00007f36cd2b1600 is at entry_point+13512 in (nmethod*) 0x00007f36cd2b1510\n"
            + "Compiled method (c2) 3068 4       com.company.Foo::processRequest (456 bytes)";
    assertThat(RedactUtils.redactRegisterToMemoryMapping(value))
        .isEqualTo(
            "0x00007f36cd2b1600 is at entry_point+13512 in (nmethod*) 0x00007f36cd2b1510\n"
                + "Compiled method (c2) 3068 4       redacted.Redacted::processRequest (456 bytes)");
  }
}
