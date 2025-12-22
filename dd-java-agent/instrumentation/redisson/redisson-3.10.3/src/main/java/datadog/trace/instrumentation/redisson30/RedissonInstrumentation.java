package datadog.trace.instrumentation.redisson30;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;
import org.redisson.api.RTransaction;
import org.redisson.client.RedisConnection;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;

@AutoService(InstrumenterModule.class)
public final class RedissonInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RedissonInstrumentation() {
    super("redisson", "redis");
  }

  @Override
  public String instrumentedType() {
    return "org.redisson.client.RedisConnection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RedissonClientDecorator",
      packageName + ".SpanFinishListener",
      packageName + ".PromiseHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandData"))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandsData"))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandsAdvice");
  }

  public static class RedissonCommandAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final CommandData<?, ?> command, @Advice.This RedisConnection thiz) {
      final CompletionStage<?> promise =
          PromiseHelper.getPromise(PromiseHelper.COMMAND_GET_PROMISE_HANDLE, command);
      if (promise == null) {
        return null;
      }
      final AgentSpan span = startSpan(RedissonClientDecorator.OPERATION_NAME);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());
      RedissonClientDecorator.DECORATE.onStatement(span, command.getCommand().getName());
      promise.whenComplete(new SpanFinishListener(AgentTracer.captureSpan(span)));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }

    public static void muzzleCheck(final RTransaction b) {
      // added on 3.10.3
      b.getBuckets();
    }
  }

  public static class RedissonCommandsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final CommandsData command, @Advice.This final RedisConnection thiz) {
      final CompletionStage<?> promise =
          PromiseHelper.getPromise(PromiseHelper.COMMANDS_GET_PROMISE_HANDLE, command);
      if (promise == null) {
        return null;
      }
      final AgentSpan span = startSpan(RedissonClientDecorator.OPERATION_NAME);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());

      List<String> commandResourceNames = new ArrayList<>();
      for (CommandData<?, ?> commandData : command.getCommands()) {
        commandResourceNames.add(commandData.getCommand().getName());
      }
      RedissonClientDecorator.DECORATE.onStatement(span, String.join(";", commandResourceNames));
      promise.whenComplete(new SpanFinishListener(AgentTracer.captureSpan(span)));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
