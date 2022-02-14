package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Map;

@AutoService(Instrumenter.class)
public class CommandImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public CommandImplInstrumentation() {
    super("vertx", "vertx-redis-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.redis.client.Command", UTF8BytesString.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.redis.client.impl.CommandImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, named("java.lang.String"))),
        packageName + ".CommandImplConstructorAdvice");
  }
}
