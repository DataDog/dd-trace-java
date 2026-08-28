package datadog.trace.test.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Provides simple commands that tests can execute in a child JVM on any operating system. */
public final class PortableCommand {
  private PortableCommand() {}

  public static String[] command(String... arguments) {
    Path executable = Paths.get(System.getProperty("java.home"), "bin", "java");
    if (!Files.isRegularFile(executable)) {
      executable = executable.resolveSibling("java.exe");
    }

    Path classpath;
    try {
      classpath =
          Paths.get(
              PortableCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Could not locate PortableCommand classes", e);
    }

    String[] command = new String[arguments.length + 4];
    command[0] = executable.toString();
    command[1] = "-cp";
    command[2] = classpath.toString();
    command[3] = PortableCommand.class.getName();
    System.arraycopy(arguments, 0, command, 4, arguments.length);
    return command;
  }

  @SuppressForbidden
  public static void main(String[] arguments) throws IOException, InterruptedException {
    switch (arguments[0]) {
      case "echo":
        System.out.println(arguments[1]);
        break;
      case "copy-input":
        byte[] buffer = new byte[1024];
        int read;
        while ((read = System.in.read(buffer)) != -1) {
          System.out.write(buffer, 0, read);
        }
        break;
      case "sleep":
        Thread.sleep(Long.parseLong(arguments[1]));
        break;
      default:
        throw new IllegalArgumentException("Unknown command: " + arguments[0]);
    }
  }
}
