package datadog.trace.test.util;

import static datadog.trace.test.util.PlatformTestUtils.normalizeLineEndings;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableCommandTest {
  @Test
  void buildsJavaCommand() {
    String[] command = PortableCommand.command("echo", "hello");

    assertTrue(Files.isRegularFile(Paths.get(command[0])));
    assertEquals("-cp", command[1]);
    assertTrue(Files.exists(Paths.get(command[2])));
    assertEquals(PortableCommand.class.getName(), command[3]);
    assertArrayEquals(new String[] {"echo", "hello"}, new String[] {command[4], command[5]});
  }

  @Test
  void resolvesJavaExecutable(@TempDir Path javaHome) throws IOException {
    Path bin = Files.createDirectories(javaHome.resolve("bin"));
    Path java = Files.createFile(bin.resolve("java"));

    assertEquals(java, PortableCommand.javaExecutable(javaHome));

    Files.delete(java);
    Path javaExe = Files.createFile(bin.resolve("java.exe"));

    assertEquals(javaExe, PortableCommand.javaExecutable(javaHome));
  }

  @Test
  void echoesArgument() throws Exception {
    Process process = new ProcessBuilder(PortableCommand.command("echo", "hello")).start();

    assertTrue(process.waitFor(10, SECONDS));
    assertEquals(0, process.exitValue());
    assertEquals("hello\n", normalizeLineEndings(readFully(process.getInputStream())));
  }

  @Test
  void executesCommands() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream printOutput = new PrintStream(output, true, UTF_8.name());

    PortableCommand.execute(
        new String[] {"echo", "hello"}, new ByteArrayInputStream(new byte[0]), printOutput);
    assertEquals("hello\n", normalizeLineEndings(new String(output.toByteArray(), UTF_8)));

    output.reset();
    PortableCommand.execute(
        new String[] {"copy-input"},
        new ByteArrayInputStream("copied".getBytes(UTF_8)),
        printOutput);
    assertEquals("copied", new String(output.toByteArray(), UTF_8));

    PortableCommand.execute(
        new String[] {"sleep", "0"}, new ByteArrayInputStream(new byte[0]), printOutput);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            PortableCommand.execute(
                new String[] {"does-not-exist"},
                new ByteArrayInputStream(new byte[0]),
                printOutput));
  }

  @Test
  void delegatesMainToCommandExecution() throws Exception {
    PortableCommand.main(new String[] {"sleep", "0"});
  }

  private static String readFully(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
    return new String(output.toByteArray(), UTF_8);
  }
}
