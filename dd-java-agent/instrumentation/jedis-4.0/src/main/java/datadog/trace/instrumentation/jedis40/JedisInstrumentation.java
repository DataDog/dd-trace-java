package datadog.trace.instrumentation.jedis40;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jedis40.JedisClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jedis40.JedisClientDecorator.REDIS_COMMAND;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.commands.ProtocolCommand;

@AutoService(Instrumenter.class)
public final class JedisInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public JedisInstrumentation() {
    super("jedis", "redis");
  }

  @Override
  public String instrumentedType() {
    return "redis.clients.jedis.Connection";
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
            .and(named("executeCommand"))
            .and(takesArgument(0, named("redis.clients.jedis.CommandObject"))),
        JedisInstrumentation.class.getName() + "$JedisAdvice");
  }

  public static class JedisAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final CommandObject<?> commandObject) {
      final AgentSpan span = startSpan(REDIS_COMMAND);
      DECORATE.afterStart(span);

      final ProtocolCommand command = commandObject.getArguments().getCommand();

      if (command instanceof Protocol.Command) {
        DECORATE.onStatement(span, ((Protocol.Command) command).name());
      } else {
        // Protocol.Command is the only implementation in the Jedis lib as of 3.1 but this will save
        // us if that changes
        DECORATE.onStatement(span, new String(command.getRaw()));
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      System.out.println("HERE       +++++++++++++++++++");
      System.out.println(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
