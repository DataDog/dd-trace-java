package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.lettuce.core.protocol.AsyncCommand;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Instrumentation attaches the active span to an {@code io.lettuce.core.protocol.AsyncCommand} so
 * the span can propagate with it into the {@code io.lettuce.core.protocol.CommandHandler} event
 * loop.
 */
@AutoService(InstrumenterModule.class)
public class AsyncCommandInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public AsyncCommandInstrumentation() {
    super("lettuce", "lettuce-5", "lettuce-5-async");
  }

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.protocol.AsyncCommand";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.lettuce.core.protocol.AsyncCommand", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(
                takesArguments(1)
                    .and(takesArgument(0, named("io.lettuce.core.protocol.RedisCommand")))),
        getClass().getName() + "$Capture");
    transformer.applyAdvice(
        isMethod().and(namedOneOf("complete", "completeExceptionally", "onComplete", "encode")),
        getClass().getName() + "$Activate");
    transformer.applyAdvice(
        isMethod().and(named("cancel")).and(takesArguments(boolean.class)),
        getClass().getName() + "$Cancel");
  }

  public static final class Capture {

    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit
    public static void after(@Advice.This AsyncCommand asyncCommand) {
      capture(InstrumentationContext.get(AsyncCommand.class, State.class), asyncCommand);
    }
  }

  public static final class Activate {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This AsyncCommand asyncCommand) {
      return startTaskScope(
          InstrumentationContext.get(AsyncCommand.class, State.class), asyncCommand);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class Cancel {
    @SuppressWarnings("rawtypes")
    @Advice.OnMethodEnter
    public static void before(@Advice.This AsyncCommand asyncCommand) {
      AdviceUtils.cancelTask(
          InstrumentationContext.get(AsyncCommand.class, State.class), asyncCommand);
    }
  }
}
