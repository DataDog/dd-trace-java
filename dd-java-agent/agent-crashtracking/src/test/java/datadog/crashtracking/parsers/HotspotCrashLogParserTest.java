package datadog.crashtracking.parsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.crashtracking.dto.CrashLog;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class HotspotCrashLogParserTest {
  @TableTest({
    "scenario         | logDateTime                     | expectedISODateTime      ",
    "2-digit offset   | Tue Oct 17 20:25:14 2023 +08    | 2023-10-17T20:25:14+08:00",
    "UTC zone         | Fri Sep 20 13:19:06 2024 UTC    | 2024-09-20T13:19:06Z     ",
    "single-digit day | Tue Oct  1 05:05:47 2024 +00:00 | 2024-10-01T05:05:47Z     ",
    "CEST zone        | Tue Oct  1 14:37:58 2024 CEST   | 2024-10-01T14:37:58+02:00"
  })
  public void testDateTimeParser(String logDateTime, String expectedISODateTime) {
    assertEquals(expectedISODateTime, HotspotCrashLogParser.dateTimeToISO(logDateTime));
  }

  @Test
  public void testIncompleteParsing() throws Exception {
    // Given
    final String uuid = UUID.randomUUID().toString();

    // When
    final CrashLog crashLog =
        new HotspotCrashLogParser().parse(uuid, readFileAsString("incomplete-crash.txt"));

    // Then
    assertNotNull(crashLog);
    assertEquals(uuid, crashLog.uuid);
    assertTrue(crashLog.incomplete);
    assertNotNull(crashLog.error);
    assertNotNull(crashLog.error.stack);
    assertNotNull(crashLog.error.stack.frames);
    assertEquals(0, crashLog.error.stack.frames.length);
    assertEquals("InternalError", crashLog.error.kind);
    assertEquals("Process terminated by Internal error", crashLog.error.message);
  }

  /** macOS aarch64 uses lowercase register names: x0-x28, fp, lr, sp, pc, cpsr */
  @Test
  public void testRegisterParsingMacosAarch64() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-macos-aarch64.txt"));

    assertNotNull(crashLog.experimental, "experimental field should be populated");
    assertNotNull(crashLog.experimental.ucontext, "ucontext should be populated");
    assertEquals("0x0000000000000c55", crashLog.experimental.ucontext.get("x0"));
    assertEquals("0x0000000000000000", crashLog.experimental.ucontext.get("x2"));
    assertEquals("0x000000016feee210", crashLog.experimental.ucontext.get("fp"));
    assertEquals("0x0000000116d0c970", crashLog.experimental.ucontext.get("lr"));
    assertEquals("0x000000016feee0f0", crashLog.experimental.ucontext.get("sp"));
    assertEquals("0x000000010f8ac794", crashLog.experimental.ucontext.get("pc"));
    assertEquals("0x0000000060001000", crashLog.experimental.ucontext.get("cpsr"));

    // "Top of Stack: (sp=0x...)" contains "=" — verify the parser stops at it and doesn't
    // absorb its hex-dump content into the last register mapping entry (sp).
    assertThat(crashLog.experimental.registerToMemoryMapping)
        .extractingByKey("sp", STRING)
        .doesNotContain("Top of Stack:");

    assertNotNull(crashLog.experimental.runtimeArgs);
    assertTrue(crashLog.experimental.runtimeArgs.contains("--enable-native-access=ALL-UNNAMED"));
    assertTrue(crashLog.experimental.runtimeArgs.contains("--add-modules=ALL-DEFAULT"));
    assertFalse(
        crashLog.experimental.runtimeArgs.stream()
            .anyMatch(arg -> arg.contains("SourceLauncher") || arg.endsWith("CrashTest.java")));
  }

  /**
   * Verifies the register-to-memory mapping section for the macOS aarch64 sample: representative
   * values, library path redaction, and that "Top of Stack:" / "Instructions:" subsections are not
   * absorbed into register values.
   */
  @Test
  public void testRegisterToMemoryMappingMacosAarch64() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-macos-aarch64.txt"));

    Map<String, String> mapping = crashLog.experimental.registerToMemoryMapping;

    // Representative single-line entries
    assertThat(mapping)
        .containsEntry("x0", "0x0000000000000c55 is an unknown value")
        .containsEntry("x2", "0x0 is null")
        .containsEntry("x28", "0x0000000100a153f0 is a thread");

    // Library path (symbol+offset format) — intermediate segments collapsed to a single /redacted
    assertThat(mapping)
        .extractingByKey("x16", STRING)
        .isEqualTo(
            "0x0000000182d709d0: pthread_jit_write_protect_np+0 in /redacted/system/libsystem_pthread.dylib at 0x0000000182d69000");
    assertThat(mapping)
        .extractingByKey("x21", STRING)
        .isEqualTo(
            "0x0000000106c1ccc0: _ZN19TemplateInterpreter13_active_tableE+0 in /redacted/server/libjvm.dylib at 0x0000000105efc000");

    // macOS aarch64 uses address+pipe format — address kept, bytes redacted
    assertThat(mapping)
        .extractingByKey("x17", STRING)
        .isEqualTo(
            "0x0000000100a17cb0 points into unknown readable memory: 0x00000000ffffffff | REDACTED");

    // "Top of Stack: (sp=0x...)" and "Instructions: (pc=0x...)" must not leak into register values
    assertThat(mapping).doesNotContainKey("Top of Stack");
    assertThat(mapping)
        .allSatisfy((k, v) -> assertThat(v).doesNotContain("Top of Stack:", "Instructions:"));

    // sp is the last register before "Top of Stack:" — its value must be clean
    assertThat(mapping)
        .extractingByKey("sp", STRING)
        .isEqualTo("0x000000016feee0f0 is pointing into the stack for thread: 0x0000000100a153f0");
  }

  /** Linux aarch64 uses uppercase register names: R0-R30 */
  @Test
  public void testRegisterParsingLinuxAarch64() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-linux-aarch64.txt"));

    assertNotNull(crashLog.experimental, "experimental field should be populated");
    assertNotNull(crashLog.experimental.ucontext, "ucontext should be populated");
    assertEquals("0x0000000000000000", crashLog.experimental.ucontext.get("R0"));
    assertEquals("0x0000000000000001", crashLog.experimental.ucontext.get("R1"));
    assertEquals("0x0000ffff9efa168c", crashLog.experimental.ucontext.get("R30"));
    assertEquals(31, crashLog.experimental.ucontext.size(), "R0-R30 = 31 registers");
  }

  @Test
  public void testRegisterToMemoryMapping() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(UUID.randomUUID().toString(), readFileAsString("sample-crash.txt"));

    assertThat(crashLog.experimental).isNotNull();
    assertThat(crashLog.experimental.registerToMemoryMapping)
        .isNotNull()
        .containsEntry(
            "RAX",
            "0x00007f36ccfbf170 points into unknown readable memory: 0x00007f3600000758 | REDACTED")
        .containsEntry(
            "RSP", "0x00007f35e6253190 is pointing into the stack for thread: 0x00007f36cd96cc80")
        .containsEntry("RDI", "0x0 is NULL")
        .containsEntry(
            "R11",
            "{method} {0x00007f3744198b70} 'resize' '()[Ljava/util/HashMap$Node;' in 'java/util/HashMap'")
        // unknown packages are fully redacted to redacted/Redacted
        .containsEntry(
            "RSI",
            "{method} {0x00007f3639c2ff00} 'saveJob' '(Lredacted/Redacted;ILjava/lang/String;)V' in 'redacted/Redacted'");
  }

  @Test
  public void testRegisterToMultilineMemoryMapping() throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-linux-aarch64.txt"));

    assertThat(crashLog.experimental).isNotNull();
    assertThat(crashLog.experimental.registerToMemoryMapping).isNotNull().containsKey("R10");
    assertThat(crashLog.experimental.registerToMemoryMapping)
        .extractingByKey("R10", STRING)
        .startsWith("0x00000007ffe85850 is an oop: java.lang.Class ")
        .contains("\n{0x00000007ffe85850} - klass: 'java/lang/Class'")
        .contains("\n - ---- fields (total size 25 words):")
        .contains(
            "\n - private transient 'name' 'Ljava/lang/String;' @44  \"jdk.internal.misc.Unsafe\"");

    // Linux aarch64 uses bytes-only format (no address prefix before hex dump)
    assertThat(crashLog.experimental.registerToMemoryMapping)
        .extractingByKey("R9", STRING)
        .isEqualTo("0x0000ffff9f686ca4 points into unknown readable memory: REDACTED");
  }

  @Test
  public void testRuntimeArgsFilteringFromHotspotJvmArgs() throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-for-telemetry.txt"));

    assertNotNull(crashLog.experimental);
    assertNotNull(crashLog.experimental.runtimeArgs);
    assertTrue(
        crashLog.experimental.runtimeArgs.contains(
            "-javaagent:/opt/REDACT_THIS/datadog-apm-agent/dd-java-agent.jar"));
    assertFalse(crashLog.experimental.runtimeArgs.contains("-Ddd.profiling.enabled=true"));
    assertFalse(crashLog.experimental.runtimeArgs.contains("-Ddd.service=REDACT_THIS"));
    assertTrue(
        crashLog.experimental.runtimeArgs.stream().anyMatch(arg -> arg.startsWith("--add-opens=")));
    assertFalse(
        crashLog.experimental.runtimeArgs.stream()
            .anyMatch(arg -> arg.startsWith("-Djavax.xml.ws.spi.Provider=")));
    assertTrue(
        crashLog.experimental.runtimeArgs.stream()
            .anyMatch(arg -> arg.startsWith("-Djava.util.logging.config.file=")));
  }

  @TableTest({
    "scenario      | filename                        ",
    "RHEL amd64    | sample-crash-for-telemetry.txt  ",
    "Ubuntu amd64  | sample-crash-for-telemetry-2.txt",
    "macOS aarch64 | sample_oom.txt                  ",
    "macOS JDK 8   | sample-crash-for-telemetry-3.txt"
  })
  public void testOsInfoFromSystemProperties(String filename) throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser().parse(UUID.randomUUID().toString(), readFileAsString(filename));
    assertNotNull(crashLog.osInfo);
  }

  @Test
  public void testFrameTypesFromHotspotStack() throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(), readFileAsString("sample-crash-for-telemetry.txt"));

    assertEquals("vm", crashLog.error.stack.frames[0].frameType);
    assertEquals("native", crashLog.error.stack.frames[3].frameType);
    assertEquals("stub", crashLog.error.stack.frames[15].frameType);
    assertEquals("compiled", crashLog.error.stack.frames[16].frameType);
    assertEquals("compiled", crashLog.error.stack.frames[17].frameType);
    assertEquals("interpreted", crashLog.error.stack.frames[66].frameType);
  }

  @Test
  public void testParsesJdk8CompiledFramesWithoutCompilerLevel() throws Exception {
    final CrashLog crashLog =
        new HotspotCrashLogParser()
            .parse(
                UUID.randomUUID().toString(),
                readFileAsString("sample-crash-jdk8-zip-getentry.txt"));

    assertEquals(10, crashLog.error.stack.frames.length);
    // native frames
    assertEquals("newEntry+0x68", crashLog.error.stack.frames[0].function);
    assertEquals("native", crashLog.error.stack.frames[0].frameType);
    assertEquals("ZIP_GetEntry2+0xec", crashLog.error.stack.frames[1].function);
    assertEquals(
        "Java_java_util_zip_ZipFile_getEntry+0xa8", crashLog.error.stack.frames[2].function);
    assertEquals("native", crashLog.error.stack.frames[2].frameType);
    assertEquals("java.util.zip.ZipFile.getEntry(J[BZ)J", crashLog.error.stack.frames[3].function);
    assertEquals("compiled", crashLog.error.stack.frames[3].frameType);
    assertEquals("0x00000001091a1f54", crashLog.error.stack.frames[3].ip);
    assertEquals("0x00000001091a1ec0", crashLog.error.stack.frames[3].symbolAddress);
    assertEquals("0x94", crashLog.error.stack.frames[3].relativeAddress);

    // Java frames
    assertEquals("java.util.zip.ZipFile.getEntry(J[BZ)J", crashLog.error.stack.frames[4].function);
    assertEquals("compiled", crashLog.error.stack.frames[4].frameType);
    assertEquals("0x00000001091a1f58", crashLog.error.stack.frames[4].ip);
    assertEquals("0x00000001091a1ec0", crashLog.error.stack.frames[4].symbolAddress);
    assertEquals("0x98", crashLog.error.stack.frames[4].relativeAddress);
    assertEquals(
        "java.util.zip.ZipFile.getEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;",
        crashLog.error.stack.frames[5].function);
    assertEquals("compiled", crashLog.error.stack.frames[5].frameType);
    assertEquals(
        "ZipFileMmapCrashRepro.lambda$main$0(ILjava/util/concurrent/CountDownLatch;Ljava/util/concurrent/atomic/AtomicBoolean;[Ljava/lang/String;Ljava/util/zip/ZipFile;)V",
        crashLog.error.stack.frames[6].function);
    assertEquals("interpreted", crashLog.error.stack.frames[6].frameType);
    assertEquals("ZipFileMmapCrashRepro$$Lambda$1.run()V", crashLog.error.stack.frames[7].function);
    assertEquals("interpreted", crashLog.error.stack.frames[7].frameType);
    assertEquals("java.lang.Thread.run()V", crashLog.error.stack.frames[8].function);
    assertEquals("interpreted", crashLog.error.stack.frames[8].frameType);
    assertEquals("~StubRoutines::call_stub", crashLog.error.stack.frames[9].function);
    assertEquals("stub", crashLog.error.stack.frames[9].frameType);
  }

  /**
   * Regression test: a {@code siginfo:} line appearing inside the {@code Register to memory
   * mapping:} section must not be appended as a continuation of the last register's value.
   */
  @Test
  public void testSiginfoLineNotAppendedToRegisterMapping() {
    // Minimal crash log fragment that drives the parser into REGISTER_TO_MEMORY_MAPPING state,
    // then encounters a siginfo: line (returned null by nextThreadSectionState — intentional, not
    // a state transition), then continues with another register entry.
    String log =
        "# A fatal error has been detected by the Java Runtime Environment:\n"
            + "# A core dump file may have been created\n"
            + "T H R E A D\n"
            + "Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)\n"
            + "Register to memory mapping:\n"
            + "\n"
            + "RSP =0x00007f35e6253190 is pointing into the stack for thread: 0x00007f36cd96cc80\n"
            + "siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x0000000000000070\n"
            + "RDI =0x0 is NULL\n";

    CrashLog crashLog = new HotspotCrashLogParser().parse(UUID.randomUUID().toString(), log);

    assertThat(crashLog.experimental).isNotNull();
    Map<String, String> mapping = crashLog.experimental.registerToMemoryMapping;
    assertThat(mapping).isNotNull();
    assertThat(mapping.get("RSP"))
        .isEqualTo("0x00007f35e6253190 is pointing into the stack for thread: 0x00007f36cd96cc80");
    assertThat(mapping.get("RSP")).doesNotContain("siginfo:");
    assertThat(mapping.get("RDI")).isEqualTo("0x0 is NULL");
  }

  @TableTest({
    "scenario                 | line                                                                                                                   | expected                          ",
    "quoted java thread       | Current thread (0x0000000caff48000):  JavaThread \"zip-reader-0\" daemon [_thread_in_native, id=18947, stack(0x1,0x2)] | JavaThread \"zip-reader-0\" daemon",
    "quoted vm thread         | Current thread (0x00007ffff044d000):  VMThread \"VM Thread\" [stack: 0x1,0x2] [id=24738]                               | VMThread \"VM Thread\"            ",
    "unquoted redacted thread | Current thread (0x00007f36cd96cc80):  JavaThread REDACT_THIS daemon [_thread_in_vm, id=2169438, stack(0x1,0x2)]        | JavaThread REDACT_THIS daemon     ",
    "gc thread                | Current thread (0x00007f5108060000):  GCTaskThread [stack: 0x00007f510c0d5000,0x00007f510c1d5000] [id=205546]          | GCTaskThread                      ",
    "concurrent gc thread     | Current thread (0x00007fd41d508800):  ConcurrentGCThread [stack: 0x00007fd3a5cfd000,0x00007fd3a5dfe000] [id=9965]      | ConcurrentGCThread                ",
    "native thread marker     | Current thread is native thread                                                                                        | null                              "
  })
  public void testParseCurrentThreadName(String line, String expected) {
    assertEquals(
        "null".equals(expected) ? null : expected,
        HotspotCrashLogParser.parseCurrentThreadName(line));
  }

  @TableTest({
    "scenario    | filename                         | expectedJreVersion                                                                                | expectedVmInfo                                                                                                                                                                                           ",
    "Zulu 17     | sample-crash-for-telemetry.txt   | OpenJDK Runtime Environment Zulu17.42+20-SA (17.0.7+7) (build 17.0.7+7-LTS)                       | OpenJDK 64-Bit Server VM (17.0.7+7-LTS) for linux-amd64 JRE (17.0.7+7-LTS) (Zulu17.42+20-SA), built on Apr 11 2023 11:39:51 by \"zulu_re\" with gcc 8.3.0                                                ",
    "Temurin 22  | sample-crash-for-telemetry-2.txt | OpenJDK Runtime Environment Temurin-22.0.1+8 (22.0.1+8) (build 22.0.1+8)                          | OpenJDK 64-Bit Server VM (22.0.1+8) for linux-amd64 JRE (22.0.1+8), built on 2024-04-16T00:00:00Z by \"admin\" with gcc 11.3.0                                                                           ",
    "Zulu 8      | sample-crash-for-telemetry-3.txt | OpenJDK Runtime Environment (Zulu 8.70.0.23-CA-macos-aarch64) (8.0_372-b07) (build 1.8.0_372-b07) | OpenJDK 64-Bit Server VM (25.372-b07) for bsd-aarch64 JRE (Zulu 8.70.0.23-CA-macos-aarch64) (1.8.0_372-b07), built on Apr 18 2023 01:36:20 by \"zulu_re\" with gcc Apple LLVM 12.0.0 (clang-1200.0.32.28)",
    "Corretto 21 | sample-crash-linux-aarch64.txt   | OpenJDK Runtime Environment Corretto-21.0.7.6.1 (21.0.7+6) (build 21.0.7+6-LTS)                   | OpenJDK 64-Bit Server VM (21.0.7+6-LTS) for linux-aarch64-musl JRE (21.0.7+6-LTS), built on 2025-04-09T23:34:45Z by \"jenkins\" with gcc 12.2.1 20220924                                                 ",
    "OpenJDK 25  | sample-crash-macos-aarch64.txt   | OpenJDK Runtime Environment (25.0.2+10) (build 25.0.2+10-69)                                      | OpenJDK 64-Bit Server VM (25.0.2+10-69) for bsd-aarch64 JRE (25.0.2+10-69), built on 2025-12-18T11:36:35Z with clang Apple LLVM 15.0.0 (clang-1500.3.9.4)                                                "
  })
  public void testRuntimeInfoParsing(
      String filename, String expectedJreVersion, String expectedVmInfo) throws Exception {
    CrashLog crashLog =
        new HotspotCrashLogParser().parse(UUID.randomUUID().toString(), readFileAsString(filename));

    assertNotNull(crashLog.experimental, "experimental should be populated");
    assertNotNull(crashLog.experimental.runtimeInfo, "runtimeInfo should be populated");
    assertNotNull(crashLog.experimental.runtimeInfo.jreVersion, "jreVersion should be populated");
    assertNotNull(crashLog.experimental.runtimeInfo.javaVm, "javaVm should be populated");
    assertNotNull(crashLog.experimental.runtimeInfo.vmInfo, "vmInfo should be populated");
    assertEquals(expectedJreVersion, crashLog.experimental.runtimeInfo.jreVersion);
    assertEquals(expectedVmInfo, crashLog.experimental.runtimeInfo.vmInfo);
  }

  @Test
  public void testNoSignalProducesInternalError() throws Exception {
    // A crash log that reaches the PROCESS section but has no siginfo line
    String crashLog =
        "# A fatal error has been detected by the Java Runtime Environment:\n"
            + "#\n"
            + "# Core dump will be written.\n"
            + "---------------  T H R E A D  ---------------\n"
            + "Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)\n"
            + "---------------  P R O C E S S  ---------------\n";

    CrashLog result = new HotspotCrashLogParser().parse(UUID.randomUUID().toString(), crashLog);

    assertNotNull(result);
    assertFalse(result.incomplete);
    assertEquals("InternalError", result.error.kind);
    assertEquals("Process terminated by Internal error", result.error.message);
  }

  private String readFileAsString(String resource) throws IOException {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
      return new BufferedReader(
              new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8))
          .lines()
          .collect(Collectors.joining("\n"));
    }
  }
}
