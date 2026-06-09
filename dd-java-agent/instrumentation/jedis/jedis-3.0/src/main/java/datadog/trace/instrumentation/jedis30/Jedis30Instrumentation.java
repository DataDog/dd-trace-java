package datadog.trace.instrumentation.jedis30;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jedis30.Jedis30ClientDecorator.COMPONENT_NAME;
import static datadog.trace.instrumentation.jedis30.Jedis30ClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.charset.StandardCharsets;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import redis.clients.jedis.Connection;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.commands.ProtocolCommand;

@AutoService(InstrumenterModule.class)
public final class Jedis30Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public Jedis30Instrumentation() {
    super("jedis", "redis");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Match Jedis 3.x only: ProtocolCommand exists in 3.x+, CommandObject exists in 4.x+
    return hasClassNamed("redis.clients.jedis.commands.ProtocolCommand")
        .and(not(hasClassNamed("redis.clients.jedis.CommandObject")));
  }

  @Override
  public String instrumentedType() {
    return "redis.clients.jedis.Connection";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Jedis30ClientDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("sendCommand"))
            .and(takesArgument(0, named("redis.clients.jedis.commands.ProtocolCommand"))),
        Jedis30Instrumentation.class.getName() + "$Jedis30Advice");
  }

  public static class Jedis30Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final ProtocolCommand command, @Advice.This final Connection thiz) {
      if (CallDepthThreadLocalMap.incrementCallDepth(Connection.class) > 0) {
        return null;
      }
      final AgentSpan span =
          startSpan(COMPONENT_NAME.toString(), Jedis30ClientDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, thiz);
      if (command instanceof Protocol.Command) {
        DECORATE.onStatement(span, ((Protocol.Command) command).name());
      } else {
        DECORATE.onStatement(span, new String(command.getRaw(), StandardCharsets.UTF_8));
      }
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
