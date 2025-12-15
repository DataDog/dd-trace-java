package datadog.environment;

import static java.util.Collections.emptyList;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.List;

/**
 * Fetches and captures the command line, both command and its arguments. It relies on a
 * non-standard {@code sun.java.command} system property and was tested on:
 *
 * <ul>
 *   <li>OracleJDK,
 *   <li>OpenJDK,
 *   <li>Temurin based JDK,
 *   <li>IMB JDK,
 *   <li>Azul Zulu,
 *   <li>Amazon Coretto,
 * </ul>
 *
 * This should be replaced by {@code ProcessHandle} and {@code ProcessHandle.Info} once Java 9+
 * become available.
 */
class CommandLine {
  private static final String SUN_JAVA_COMMAND_PROPERTY = "sun.java.command";
  private final List<String> fullCommand = findFullCommand();
  final String name = getCommandName();
  final List<String> arguments = getCommandArguments();

  @SuppressForbidden // split on single-character uses a fast path
  private List<String> findFullCommand() {
    String command = SystemProperties.getOrDefault(SUN_JAVA_COMMAND_PROPERTY, "").trim();
    return command.isEmpty() ? emptyList() : Arrays.asList(command.split(" "));
  }

  private String getCommandName() {
    return this.fullCommand.isEmpty() ? null : this.fullCommand.get(0);
  }

  private List<String> getCommandArguments() {
    if (this.fullCommand.isEmpty()) {
      return this.fullCommand;
    } else {
      return this.fullCommand.subList(1, this.fullCommand.size());
    }
  }
}
