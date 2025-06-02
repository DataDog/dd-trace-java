package datadog.environment;

import static java.util.Collections.emptyList;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.List;

/** Fetches and captures the command line, both command and its arguments. */
class CommandLine {
  final List<String> FULL_CMD = findFullCommand();
  final String CMD = getCommand();
  final List<String> CMD_ARGUMENTS = getCommandArguments();

  @SuppressForbidden // split on single-character uses fast path
  private List<String> findFullCommand() {
    // Besides "sun.java.command" property is not an standard, all main JDKs has set this
    // property.
    // Tested on:
    // - OracleJDK, OpenJDK, AdoptOpenJDK, IBM JDK, Azul Zulu JDK, Amazon Coretto JDK
    String command = SystemProperties.getOrDefault("sun.java.command", "").trim();
    return command.isEmpty() ? emptyList() : Arrays.asList(command.split(" "));
  }

  private String getCommand() {
    return FULL_CMD.isEmpty() ? null : FULL_CMD.get(0);
  }

  private List<String> getCommandArguments() {
    if (FULL_CMD.isEmpty()) {
      return FULL_CMD;
    } else {
      return FULL_CMD.subList(1, FULL_CMD.size());
    }
  }
}
