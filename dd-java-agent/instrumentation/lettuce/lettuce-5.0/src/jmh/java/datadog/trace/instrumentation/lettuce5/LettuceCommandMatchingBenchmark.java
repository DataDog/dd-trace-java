package datadog.trace.instrumentation.lettuce5;

import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.protocol.RedisCommand;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares the old {@code HashSet<String>} lookup in {@link
 * LettuceInstrumentationUtil#expectsResponse(RedisCommand)} against the new {@code
 * EnumSet<CommandType>} lookup. The win is lookup strategy (bit-set test vs. string hash/equals),
 * not avoided allocation -- these {@code CommandType} names have no whitespace, so {@code
 * toString().trim()} never allocates. {@code oldExpectsResponse} reproduces the removed
 * implementation for comparison.
 *
 * <p>Split into two traffic shapes: {@code Miss} (ordinary data commands, ~100% of real traffic)
 * and {@code Hit} (DEBUG/SHUTDOWN, rare but exercises the matching path).
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:lettuce:lettuce-5.0:jmh   # add -prof gc
 * </pre>
 */
@Fork(3)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 5, time = 5)
@Threads(1)
public class LettuceCommandMatchingBenchmark {

  /** Byte-for-byte reproduction of the {@code Set<String>}-based check this replaces. */
  private static final String[] NON_INSTRUMENTING_COMMAND_WORDS =
      new String[] {"SHUTDOWN", "DEBUG", "OOM", "SEGFAULT"};

  private static final Set<String> NON_INSTRUMENTING_COMMANDS_OLD =
      new HashSet<>(Arrays.asList(NON_INSTRUMENTING_COMMAND_WORDS));

  private static boolean oldExpectsResponse(final RedisCommand command) {
    String commandName = "Redis Command";
    if (command != null && command.getType() != null) {
      commandName = command.getType().toString().trim();
    }
    return !NON_INSTRUMENTING_COMMANDS_OLD.contains(commandName);
  }

  /** Minimal {@link RedisCommand} stub -- only {@link #getType()} is ever exercised here. */
  private static final class FakeRedisCommand implements RedisCommand<Object, Object, Object> {
    private final ProtocolKeyword type;

    FakeRedisCommand(final ProtocolKeyword type) {
      this.type = type;
    }

    @Override
    public ProtocolKeyword getType() {
      return type;
    }

    @Override
    public CommandOutput<Object, Object, Object> getOutput() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void complete() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean completeExceptionally(final Throwable throwable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cancel() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CommandArgs<Object, Object> getArgs() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void encode(final ByteBuf buf) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCancelled() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDone() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setOutput(final CommandOutput<Object, Object, Object> output) {
      throw new UnsupportedOperationException();
    }
  }

  // Representative production traffic: ordinary data commands, none of which ever match
  // NON_INSTRUMENTING_COMMANDS.
  private static final RedisCommand[] MISS_COMMANDS =
      Arrays.stream(
              new CommandType[] {
                CommandType.GET,
                CommandType.SET,
                CommandType.EXISTS,
                CommandType.EXPIRE,
                CommandType.HSET,
                CommandType.LPUSH,
                CommandType.INCR,
              })
          .map(FakeRedisCommand::new)
          .toArray(RedisCommand[]::new);

  // Rare admin commands that always match NON_INSTRUMENTING_COMMANDS. Not representative of real
  // traffic volume -- included only to exercise the hit path.
  private static final RedisCommand[] HIT_COMMANDS =
      Arrays.stream(new CommandType[] {CommandType.DEBUG, CommandType.SHUTDOWN})
          .map(FakeRedisCommand::new)
          .toArray(RedisCommand[]::new);

  private abstract static class Cursor {
    int index = 0;

    abstract RedisCommand[] commands();

    RedisCommand next() {
      final RedisCommand[] commands = commands();
      final int i = index;
      index = (i + 1) % commands.length;
      return commands[i];
    }
  }

  @State(Scope.Thread)
  public static class MissCursor extends Cursor {
    @Override
    RedisCommand[] commands() {
      return MISS_COMMANDS;
    }
  }

  @State(Scope.Thread)
  public static class HitCursor extends Cursor {
    @Override
    RedisCommand[] commands() {
      return HIT_COMMANDS;
    }
  }

  @Benchmark
  public boolean missOld(final MissCursor cursor) {
    return oldExpectsResponse(cursor.next());
  }

  @Benchmark
  public boolean missNew(final MissCursor cursor) {
    return LettuceInstrumentationUtil.expectsResponse(cursor.next());
  }

  @Benchmark
  public boolean hitOld(final HitCursor cursor) {
    return oldExpectsResponse(cursor.next());
  }

  @Benchmark
  public boolean hitNew(final HitCursor cursor) {
    return LettuceInstrumentationUtil.expectsResponse(cursor.next());
  }
}
