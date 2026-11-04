package datadog.trace.bootstrap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.io.Resources;
import datadog.trace.test.util.PollingConditions;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AgentPreCheckTest {

  @TableTest({
    "scenario     | version      | expected",
    "null         |              | 0       ",
    "empty        | ''           | 0       ",
    "a.0.0        | 'a.0.0'      | 0       ",
    "0.a.0        | '0.a.0'      | 0       ",
    "0.0.a        | '0.0.a'      | 0       ",
    "1.a.0_0      | '1.a.0_0'    | 1       ",
    "1.6          | '1.6'        | 6       ",
    "1.6.0_45     | '1.6.0_45'   | 6       ",
    "1.7          | '1.7'        | 7       ",
    "1.7.0        | '1.7.0'      | 7       ",
    "1.7.0_221    | '1.7.0_221'  | 7       ",
    "1.8.a_0      | '1.8.a_0'    | 8       ",
    "1.8.0_a      | '1.8.0_a'    | 8       ",
    "1.8          | '1.8'        | 8       ",
    "1.8.0        | '1.8.0'      | 8       ",
    "1.8.0_212    | '1.8.0_212'  | 8       ",
    "1.8.0_292    | '1.8.0_292'  | 8       ",
    "9-ea         | '9-ea'       | 9       ",
    "9.0.4        | '9.0.4'      | 9       ",
    "9.1.2        | '9.1.2'      | 9       ",
    "10.0.2       | '10.0.2'     | 10      ",
    "11           | '11'         | 11      ",
    "11a          | '11a'        | 11      ",
    "11.0.6       | '11.0.6'     | 11      ",
    "11.0.11      | '11.0.11'    | 11      ",
    "12.0.2       | '12.0.2'     | 12      ",
    "13.0.2       | '13.0.2'     | 13      ",
    "14           | '14'         | 14      ",
    "14.0.2       | '14.0.2'     | 14      ",
    "15           | '15'         | 15      ",
    "15.0.2       | '15.0.2'     | 15      ",
    "16.0.1       | '16.0.1'     | 16      ",
    "'11.0.9.1+1' | '11.0.9.1+1' | 11      ",
    "'11.0.6+10'  | '11.0.6+10'  | 11      ",
    "17.0.15      | '17.0.15'    | 17      ",
    "21.0.7       | '21.0.7'     | 21      "
  })
  void parseJavaVersion(String version, int expected) {
    // when
    int major = AgentPreCheck.parseJavaMajorVersion(version);
    // then
    assertEquals(expected, major);
  }

  @TableTest({
    "scenario  | javaVersion | expectedCompatible",
    "null      |             | false             ",
    "empty     | ''          | false             ",
    "1.6.0_45  | '1.6.0_45'  | false             ",
    "1.7.0_221 | '1.7.0_221' | false             ",
    "1.8.0_212 | '1.8.0_212' | true              ",
    "11.0.6    | '11.0.6'    | true              ",
    "17.0.15   | '17.0.15'   | true              ",
    "21.0.7    | '21.0.7'    | true              "
  })
  void logWarningMessageWhenJavaIsNotCompatible(String javaVersion, boolean expectedCompatible)
      throws IOException {
    // setup
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream logStream = new PrintStream(output);
    String javaHome = "/Library/" + javaVersion;
    // when
    boolean compatible = AgentPreCheck.compatible(javaVersion, javaHome, logStream);
    String log = output.toString();
    List<String> logLines = log.isEmpty() ? emptyList() : asList(log.split("\n"));
    // then
    assertEquals(expectedCompatible, compatible);
    if (expectedCompatible) {
      assertTrue(logLines.isEmpty());
    } else {
      List<String> expectedLogLines =
          asList(
              "Warning: Version "
                  + AgentJar.getAgentVersion()
                  + " of dd-java-agent is not compatible with Java "
                  + javaVersion
                  + " found at '"
                  + javaHome
                  + "' and is effectively disabled.",
              "Please upgrade your Java version to 8+");
      assertEquals(expectedLogLines, logLines);
    }
  }

  @Test
  void sendHardcodedBootstrapTelemetryForUnsupportedJava() throws Exception {
    // setup
    Path path =
        Files.createTempFile(
            "test-forwarder",
            ".sh",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr--r--")));
    File forwarderFile = path.toFile();
    forwarderFile.deleteOnExit();

    String forwarderPath = forwarderFile.getAbsolutePath();

    File outputFile = new File(forwarderPath + ".out");
    outputFile.deleteOnExit();

    String script =
        "#!/usr/bin/env bash\n" + "echo \"$1\t$(cat -)\" >>" + outputFile.getAbsolutePath() + "\n";
    Files.write(path, script.getBytes(UTF_8));

    // when
    AgentPreCheck.sendTelemetry(forwarderPath, "1.6.0_45", "1.50");

    // then
    // Await completion of the external process handling the payload.
    new PollingConditions(5).eventually(() -> assertTrue(outputFile.exists()));
    String payload = new String(Files.readAllBytes(outputFile.toPath()), UTF_8);

    // The forwarder script writes "<telemetry type><TAB><payload>".
    String prefix = "library_entrypoint\t";
    assertTrue(payload.startsWith(prefix), payload);
    String json = payload.substring(prefix.length());
    String expectedJson =
        Resources.toString(
            Resources.getResource(AgentPreCheckTest.class, "unsupported-java-telemetry.json"),
            UTF_8);
    assertThatJson(json).isEqualTo(expectedJson);
  }

  @TableTest({
    "scenario                       | clazz                                  | expectedMajor",
    "AgentPreCheck compiled with 6  | datadog.trace.bootstrap.AgentPreCheck  | 50           ",
    "AgentBootstrap compiled with 8 | datadog.trace.bootstrap.AgentBootstrap | 52           "
  })
  void checkClassCompiledWithJavaVersion(Class<?> clazz, int expectedMajor) throws Exception {
    String resource = clazz.getName().replace('.', '/') + ".class";
    try (DataInputStream stream =
        new DataInputStream(getClass().getClassLoader().getResourceAsStream(resource))) {
      long magic = Integer.toUnsignedLong(stream.readInt());
      int minor = stream.readShort();
      int major = stream.readShort();

      assertEquals(0xCAFEBABEL, magic);
      assertEquals(0, minor);
      assertEquals(expectedMajor, major);
    }
  }
}
