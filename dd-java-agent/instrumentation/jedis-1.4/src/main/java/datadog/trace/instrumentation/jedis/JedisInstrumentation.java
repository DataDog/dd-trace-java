package datadog.trace.instrumentation.jedis;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol.Command;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jedis.JedisClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class JedisInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
            .and(named("sendCommand"))
            .and(takesArgument(0, named("redis.clients.jedis.Protocol$Command"))),
        JedisInstrumentation.class.getName() + "$JedisAdvice");
    // FIXME: This instrumentation only incorporates sending the command, not processing the result.
  }

  public static class JedisAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final Command command, @Advice.This final Connection thiz,@Advice.Argument(2)final byte[][] args) {
      if (CallDepthThreadLocalMap.incrementCallDepth(Connection.class) > 0) {
        return null;
      }
      final AgentSpan span = startSpan(JedisClientDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, thiz);
      DECORATE.onStatement(span, command.name());
      //System.out.println("------- set raw "+ new String(command.raw));
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
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(Connection.class);
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
