package datadog.trace.instrumentation.rediscala;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.actor.ActorRef;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import redis.RedisClientActorLike;

@AutoService(InstrumenterModule.class)
public class RedisClientActorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public RedisClientActorInstrumentation() {
    super("rediscala", "redis", "rediscala-connection");
  }

  @Override
  public String hierarchyMarkerType() {
    return "redis.RedisClientActorLike";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".RedisConnectionInfo"};
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("akka.actor.ActorRef", packageName + ".RedisConnectionInfo");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("onConnect")),
        RedisClientActorInstrumentation.class.getName() + "$ConnectionAdvice");
  }

  public static class ConnectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeOnConnect(@Advice.This final RedisClientActorLike thiz) {
      if (thiz.redisConnection() != null) {
        final Object tmpDbIndex = thiz.db().isDefined() ? thiz.db().get() : null;
        final int dbIndex = (tmpDbIndex instanceof Number) ? ((Number) tmpDbIndex).intValue() : 0;
        InstrumentationContext.get(ActorRef.class, RedisConnectionInfo.class)
            .put(
                thiz.redisConnection(), new RedisConnectionInfo(thiz.host(), thiz.port(), dbIndex));
      }
    }
  }
}
