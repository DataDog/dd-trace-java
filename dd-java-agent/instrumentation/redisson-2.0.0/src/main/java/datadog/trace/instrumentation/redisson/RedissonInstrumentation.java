package datadog.trace.instrumentation.redisson;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.redisson.RedissonClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.redisson.client.RedisConnection;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;

@AutoService(Instrumenter.class)
public final class RedissonInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
      final AgentSpan span = startSpan(RedissonClientDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());
      DECORATE.onStatement(span, command.getCommand().getName());
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }

  public static class RedissonCommandsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final CommandsData command, @Advice.This final RedisConnection thiz) {
      final AgentSpan span = startSpan(RedissonClientDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());

      List<String> commandResourceNames = new ArrayList<>();
      for (CommandData<?, ?> commandData : command.getCommands()) {
        commandResourceNames.add(commandData.getCommand().getName());
      }
      DECORATE.onStatement(span, Strings.join(";", commandResourceNames));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
