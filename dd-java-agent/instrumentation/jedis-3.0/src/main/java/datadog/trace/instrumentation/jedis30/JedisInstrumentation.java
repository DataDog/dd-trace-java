package datadog.trace.instrumentation.jedis30;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.commands.ProtocolCommand;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jedis30.JedisClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jedis30.JedisClientDecorator.REDIS_COMMAND;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class JedisInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public JedisInstrumentation() {
    super("jedis", "redis");
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
            .and(takesArgument(1, named("redis.clients.jedis.commands.ProtocolCommand"))),
        JedisInstrumentation.class.getName() + "$JedisAdvice");
    // FIXME: This instrumentation only incorporates sending the command, not processing the result.
  }

  public static class JedisAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(1) final ProtocolCommand command,
                                     @Advice.Argument(2)final byte[][] args ) {
      final AgentSpan span = startSpan(REDIS_COMMAND);
      DECORATE.afterStart(span);
      if (command instanceof Protocol.Command) {
        DECORATE.onStatement(span, ((Protocol.Command) command).name());
      } else {
        // Protocol.Command is the only implementation in the Jedis lib as of 3.1 but this will save
        // us if that changes
        DECORATE.onStatement(span, new String(command.getRaw()));
      }
      StringBuilder args1 = new StringBuilder();
      for(int i = 0; i < args.length; i++) {
        args1.append(new String(args[i]));
        args1.append(" ");
      }

      DECORATE.setRaw(span,args1.toString());
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
