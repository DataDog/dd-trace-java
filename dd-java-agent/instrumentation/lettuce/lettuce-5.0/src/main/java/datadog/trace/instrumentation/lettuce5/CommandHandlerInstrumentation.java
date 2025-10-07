package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.RedisCommand;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation activates the span associated with {@code
 * io.lettuce.core.protocol.AsyncCommand} during decoding.
 */
@AutoService(InstrumenterModule.class)
public class CommandHandlerInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CommandHandlerInstrumentation() {
    super("lettuce", "lettuce-5", "lettuce-5-async");
  }

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.protocol.CommandHandler";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.lettuce.core.protocol.AsyncCommand", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("decode"))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, named("io.netty.buffer.ByteBuf")))
            .and(takesArgument(2, named("io.lettuce.core.protocol.RedisCommand"))),
        getClass().getName() + "$Decode");
  }

  public static class Decode {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.Argument(2) RedisCommand command) {
      // if it's something we're tracing, it will always be an AsyncCommand
      if (command instanceof AsyncCommand) {
        return startTaskScope(
            InstrumentationContext.get(AsyncCommand.class, State.class), (AsyncCommand) command);
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
