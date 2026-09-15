package datadog.trace.test.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.internal.VisibleForTesting;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Emulates the utilities described by {@link PortableCommand} inside a JVM, for platforms that have
 * no usable native equivalent. Spawned as a child process; not meant to be called directly.
 */
public final class PortableCommandRunner {
  private PortableCommandRunner() {}

  @SuppressForbidden
  public static void main(String[] arguments) throws IOException, InterruptedException {
    execute(arguments, System.in, System.out);
    System.out.flush();
  }

  @VisibleForTesting
  static void execute(String[] arguments, InputStream input, OutputStream output)
      throws IOException, InterruptedException {
    if (arguments.length == 0) {
      throw new IllegalArgumentException("Missing command");
    }
    switch (arguments[0]) {
      case "echo":
        output.write((argument(arguments) + System.lineSeparator()).getBytes(UTF_8));
        break;
      case "cat":
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
        }
        break;
      case "sleep":
        Thread.sleep(Long.parseLong(argument(arguments)));
        break;
      default:
        throw new IllegalArgumentException("Unknown command: " + arguments[0]);
    }
  }

  private static String argument(String[] arguments) {
    if (arguments.length < 2) {
      throw new IllegalArgumentException("Command '" + arguments[0] + "' requires an argument");
    }
    return arguments[1];
  }
}
