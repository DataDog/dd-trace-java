package datadog.trace.instrumentation.lettuce5;

import io.lettuce.core.protocol.RedisCommand;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LettuceInstrumentationUtil {

  public static final String[] NON_INSTRUMENTING_COMMAND_WORDS =
      new String[] {"SHUTDOWN", "DEBUG", "OOM", "SEGFAULT"};

  public static final String[] AGENT_CRASHING_COMMANDS_WORDS =
      new String[] {"CLIENT", "CLUSTER", "COMMAND", "CONFIG", "DEBUG", "SCRIPT"};

  public static final String AGENT_CRASHING_COMMAND_PREFIX = "COMMAND-NAME:";

  public static final Set<String> nonInstrumentingCommands =
      new HashSet<>(Arrays.asList(NON_INSTRUMENTING_COMMAND_WORDS));

  public static final Set<String> agentCrashingCommands =
      new HashSet<>(Arrays.asList(AGENT_CRASHING_COMMANDS_WORDS));

  /**
   * Determines whether a redis command should finish its relevant span early (as soon as tags are
   * added and the command is executed) because these commands have no return values/call backs, so
   * we must close the span early in order to provide info for the users
   *
   * @param command
   * @return false if the span should finish early (the command will not have a return value)
   */
  public static boolean expectsResponse(final RedisCommand command) {
    final String commandName = LettuceInstrumentationUtil.getCommandName(command);
    return !nonInstrumentingCommands.contains(commandName);
  }

  // Workaround to keep trace agent from crashing
  // Currently the commands in AGENT_CRASHING_COMMANDS_WORDS will crash the trace agent and
  // traces with these commands as the resource name will not be processed by the trace agent
  // https://github.com/DataDog/datadog-trace-agent/blob/master/quantizer/redis.go#L18 has
  // list of commands that will currently fail at the trace agent level.

  /**
   * Workaround to keep trace agent from crashing Currently the commands in
   * AGENT_CRASHING_COMMANDS_WORDS will crash the trace agent and traces with these commands as the
   * resource name will not be processed by the trace agent
   * https://github.com/DataDog/datadog-trace-agent/blob/master/quantizer/redis.go#L18 has list of
   * commands that will currently fail at the trace agent level.
   *
   * @param actualCommandName the actual redis command
   * @return the redis command with a prefix if it is a command that will crash the trace agent,
   *     otherwise, the original command is returned.
   */
  public static String getCommandResourceName(final String actualCommandName) {
    if (agentCrashingCommands.contains(actualCommandName)) {
      return AGENT_CRASHING_COMMAND_PREFIX + actualCommandName;
    }
    return actualCommandName;
  }

  /**
   * Retrieves the actual redis command name from a RedisCommand object
   *
   * @param command the lettuce RedisCommand object
   * @return the redis command as a string
   */
  public static String getCommandName(final RedisCommand command) {
    String commandName = "Redis Command";
    if (command != null) {

      // get the redis command name (i.e. GET, SET, HMSET, etc)
      if (command.getType() != null) {
        commandName = command.getType().name().trim();
      }
    }
    return commandName;
  }
}
