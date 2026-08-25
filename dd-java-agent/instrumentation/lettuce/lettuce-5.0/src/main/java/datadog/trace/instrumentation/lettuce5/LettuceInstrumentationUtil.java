package datadog.trace.instrumentation.lettuce5;

import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.RedisCommand;
import java.util.EnumSet;
import java.util.Set;

public class LettuceInstrumentationUtil {

  // DEBUG covers both `DEBUG OOM` and `DEBUG SEGFAULT`: Lettuce always encodes those as a command
  // of type DEBUG with "OOM"/SEGFAULT passed as an argument, never as the command type itself.
  public static final Set<CommandType> NON_INSTRUMENTING_COMMANDS =
      EnumSet.of(CommandType.SHUTDOWN, CommandType.DEBUG);

  public static final Set<CommandType> AGENT_CRASHING_COMMANDS =
      EnumSet.of(
          CommandType.CLIENT,
          CommandType.CLUSTER,
          CommandType.COMMAND,
          CommandType.CONFIG,
          CommandType.DEBUG,
          CommandType.SCRIPT);

  public static final String AGENT_CRASHING_COMMAND_PREFIX = "COMMAND-NAME:";

  /**
   * Determines whether a redis command should finish its relevant span early (as soon as tags are
   * added and the command is executed) because these commands have no return values/call backs, so
   * we must close the span early in order to provide info for the users
   *
   * @param command
   * @return false if the span should finish early (the command will not have a return value)
   */
  public static boolean expectsResponse(final RedisCommand command) {
    if (command == null) {
      return true;
    }
    final ProtocolKeyword type = command.getType();
    return !(type instanceof CommandType && NON_INSTRUMENTING_COMMANDS.contains(type));
  }

  /**
   * Workaround to keep trace agent from crashing Currently the commands in AGENT_CRASHING_COMMANDS
   * will crash the trace agent and traces with these commands as the resource name will not be
   * processed by the trace agent
   * https://github.com/DataDog/datadog-trace-agent/blob/master/quantizer/redis.go#L18 has list of
   * commands that will currently fail at the trace agent level.
   *
   * @param command the lettuce RedisCommand object
   * @return the redis command with a prefix if it is a command that will crash the trace agent,
   *     otherwise, the original command is returned.
   */
  public static String getCommandResourceName(final RedisCommand command) {
    final String commandName = getCommandName(command);
    final ProtocolKeyword type = command == null ? null : command.getType();
    if (type instanceof CommandType && AGENT_CRASHING_COMMANDS.contains(type)) {
      return AGENT_CRASHING_COMMAND_PREFIX + commandName;
    }
    return commandName;
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
        commandName = command.getType().toString().trim();
      }
    }
    return commandName;
  }
}
