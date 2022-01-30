package datadog.trace.instrumentation.redisson;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class RedissonInstrumentation extends Instrumenter.Tracing {

  public RedissonInstrumentation() {
    super("redisson", "redis");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.redisson.client.RedisConnection");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".RedissonClientDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandData"))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandsData"))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandsAdvice");
  }

  public static class RedissonCommandAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final CommandData<?, ?> command) {
      final AgentSpan span = startSpan(RedissonClientDecorator.REDIS_COMMAND);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onStatement(span, command.getCommand().getName());
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      RedissonClientDecorator.DECORATE.onError(scope.span(), throwable);
      RedissonClientDecorator.DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }

  public static class RedissonCommandsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final CommandsData command) {
      final AgentSpan span = startSpan(RedissonClientDecorator.REDIS_COMMAND);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onStatement(span, command.toString());
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      RedissonClientDecorator.DECORATE.onError(scope.span(), throwable);
      RedissonClientDecorator.DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
