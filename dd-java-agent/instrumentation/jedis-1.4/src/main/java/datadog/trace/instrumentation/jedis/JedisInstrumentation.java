package datadog.trace.instrumentation.jedis;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jedis.JedisClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jedis.JedisClientDecorator.REDIS_COMMAND;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import redis.clients.jedis.Protocol.Command;

@AutoService(Instrumenter.class)
public final class JedisInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final String SERVICE_NAME = "redis";
  private static final String COMPONENT_NAME = SERVICE_NAME + "-command";

  public JedisInstrumentation() {
    super("jedis", "redis");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Avoid matching Jedis 3+ which has its own instrumentation.
    return not(hasClassNamed("redis.clients.jedis.commands.ProtocolCommand"));
  }

  @Override
  public String instrumentedType() {
    return "redis.clients.jedis.Protocol";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JedisClientDecorator",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("sendCommand"))
            .and(takesArgument(1, named("redis.clients.jedis.Protocol$Command"))),
        JedisInstrumentation.class.getName() + "$JedisAdvice");
    // FIXME: This instrumentation only incorporates sending the command, not processing the result.
  }

  public static class JedisAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(1) final Command command) {
      final AgentSpan span = startSpan(REDIS_COMMAND);
      DECORATE.afterStart(span);
      DECORATE.onStatement(span, command.name());
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
