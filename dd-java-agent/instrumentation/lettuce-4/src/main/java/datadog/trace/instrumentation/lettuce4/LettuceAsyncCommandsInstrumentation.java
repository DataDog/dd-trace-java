package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;

@AutoService(Instrumenter.class)
public class LettuceAsyncCommandsInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public LettuceAsyncCommandsInstrumentation() {
    super("lettuce", "lettuce-4", "lettuce-4-async");
  }

  @Override
  public String instrumentedType() {
    return "com.lambdaworks.redis.AbstractRedisAsyncCommands";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.lambdaworks.redis.api.StatefulConnection", "com.lambdaworks.redis.RedisURI");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator", packageName + ".InstrumentationPoints"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("dispatch"))
            .and(takesArgument(0, named("com.lambdaworks.redis.protocol.RedisCommand"))),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".LettuceAsyncCommandsAdvice");
  }
}
