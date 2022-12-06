package datadog.trace.instrumentation.lettuce4;

import static com.lambdaworks.redis.protocol.CommandKeyword.SEGFAULT;
import static com.lambdaworks.redis.protocol.CommandType.CLIENT;
import static com.lambdaworks.redis.protocol.CommandType.CLUSTER;
import static com.lambdaworks.redis.protocol.CommandType.COMMAND;
import static com.lambdaworks.redis.protocol.CommandType.CONFIG;
import static com.lambdaworks.redis.protocol.CommandType.DEBUG;
import static com.lambdaworks.redis.protocol.CommandType.SCRIPT;
import static com.lambdaworks.redis.protocol.CommandType.SHUTDOWN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.lettuce4.LettuceClientDecorator.DECORATE;
import static datadog.trace.instrumentation.lettuce4.LettuceClientDecorator.REDIS_QUERY;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.protocol.AsyncCommand;
import com.lambdaworks.redis.protocol.CommandType;
import com.lambdaworks.redis.protocol.ProtocolKeyword;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class InstrumentationPoints {

  private static final Set<CommandType> NON_INSTRUMENTING_COMMANDS = EnumSet.of(SHUTDOWN, DEBUG);

  private static final Set<CommandType> AGENT_CRASHING_COMMANDS =
      EnumSet.of(CLIENT, CLUSTER, COMMAND, CONFIG, DEBUG, SCRIPT);

  public static final String AGENT_CRASHING_COMMAND_PREFIX = "COMMAND-NAME:";

  public static AgentScope beforeCommand(final RedisCommand<?, ?, ?> command) {
    final AgentSpan span = startSpan(REDIS_QUERY);
    DECORATE.afterStart(span);
    DECORATE.onCommand(span, command);
    return activateSpan(span);
  }

  public static void afterCommand(
      final RedisCommand<?, ?, ?> command,
      final AgentScope scope,
      final Throwable throwable,
      final AsyncCommand<?, ?, ?> asyncCommand) {
    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      span.finish();
    } else if (expectsResponse(command)) {
      asyncCommand.handleAsync(
          (value, ex) -> {
            if (ex instanceof CancellationException) {
              span.setTag("db.command.cancelled", true);
            } else {
              DECORATE.onError(span, ex);
            }
            DECORATE.beforeFinish(span);
            span.finish();
            return null;
          });
    } else {
      // No response is expected, so we must finish the span now.
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();
    // span may be finished by handleAsync call above.
  }

  public static AgentScope beforeConnect(final RedisURI redisURI) {
    final AgentSpan span = startSpan(REDIS_QUERY);
    DECORATE.afterStart(span);
    DECORATE.onConnection(span, redisURI);
    return activateSpan(span);
  }

  public static void afterConnect(final AgentScope scope, final Throwable throwable) {
    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
    }
    scope.close();
    span.finish();
  }

  /**
   * Determines whether a redis command should finish its relevant span early (as soon as tags are
   * added and the command is executed) because these commands have no return values/call backs, so
   * we must close the span early in order to provide info for the users
   *
   * @param command
   * @return false if the span should finish early (the command will not have a return value)
   */
  public static boolean expectsResponse(final RedisCommand<?, ?, ?> command) {
    final ProtocolKeyword keyword = command.getType();
    return !(isNonInstrumentingCommand(keyword) || isNonInstrumentingKeyword(keyword));
  }

  private static boolean isNonInstrumentingCommand(final ProtocolKeyword keyword) {
    return keyword instanceof CommandType && NON_INSTRUMENTING_COMMANDS.contains(keyword);
  }

  private static boolean isNonInstrumentingKeyword(final ProtocolKeyword keyword) {
    return keyword == SEGFAULT;
  }

  /**
   * Workaround to keep trace agent from crashing Currently the commands in AGENT_CRASHING_COMMANDS
   * will crash the trace agent and traces with these commands as the resource name will not be
   * processed by the trace agent
   *
   * @param keyword the actual redis command
   * @return the redis command with a prefix if it is a command that will crash the trace agent,
   *     otherwise, the original command is returned.
   */
  public static String getCommandResourceName(final ProtocolKeyword keyword) {
    if (keyword instanceof CommandType && AGENT_CRASHING_COMMANDS.contains(keyword)) {
      return AGENT_CRASHING_COMMAND_PREFIX + keyword.name();
    }
    return keyword.name();
  }
}
