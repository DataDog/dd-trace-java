package datadog.trace.instrumentation.rediscala;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.rediscala.RediscalaClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.actor.ActorRef;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import redis.ActorRequest;
import redis.RedisCommand;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

@AutoService(InstrumenterModule.class)
public final class RediscalaInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RediscalaInstrumentation() {
    super("rediscala", "redis");
  }

  @Override
  public String hierarchyMarkerType() {
    return "redis.Request";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return NameMatchers.nameStartsWith("redis.")
        .and(
            implementsInterface(
                namedOneOf( // traits
                    "redis.Request",
                    "redis.ActorRequest",
                    "redis.BufferedRequest",
                    "redis.RoundRobinPoolRequest")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OnCompleteHandler",
      packageName + ".RediscalaClientDecorator",
      packageName + ".RedisConnectionInfo"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("akka.actor.ActorRef", packageName + ".RedisConnectionInfo");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("redis.RedisCommand")))
            .and(returns(named("scala.concurrent.Future"))),
        RediscalaInstrumentation.class.getName() + "$RediscalaAdvice");
  }

  public static class RediscalaAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final RedisCommand cmd) {
      final AgentSpan span = startSpan(RediscalaClientDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onStatement(span, DECORATE.className(cmd.getClass()));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.This final Object thiz,
        @Advice.FieldValue("executionContext") final ExecutionContext ctx,
        @Advice.Return(readOnly = false) final Future<Object> responseFuture) {

      final AgentSpan span = scope.span();
      final ContextStore<ActorRef, RedisConnectionInfo> contextStore =
          InstrumentationContext.get(ActorRef.class, RedisConnectionInfo.class);
      ActorRef connection = null;
      if (thiz instanceof ActorRequest) {
        connection = ((ActorRequest) thiz).redisConnection();
      }
      if (throwable == null) {
        responseFuture.onComplete(new OnCompleteHandler(contextStore, connection), ctx);
      } else {
        if (connection != null) {
          // try to get the info early
          DECORATE.onConnection(span, contextStore.get(connection));
        }
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
      scope.close();
      // span finished in OnCompleteHandler
    }
  }
}
