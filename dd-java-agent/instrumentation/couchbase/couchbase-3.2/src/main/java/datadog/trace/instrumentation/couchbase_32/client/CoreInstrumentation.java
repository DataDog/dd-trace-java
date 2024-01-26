package datadog.trace.instrumentation.couchbase_32.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.env.SeedNode;
import com.couchbase.client.core.util.ConnectionString;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CoreInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public CoreInstrumentation() {
    super("couchbase");
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.Core";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.couchbase.client.core.Core", String.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SeedNodeHelper", packageName + ".ConnectionStringHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(2, named("java.util.Set"))),
        CoreInstrumentation.class.getName() + "$CoreConstructorSeedNodeAdvice");
    transformer.applyAdvice(
        isConstructor()
            .and(takesArgument(2, named("com.couchbase.client.core.util.ConnectionString"))),
        CoreInstrumentation.class.getName() + "$CoreConstructorConnectionStringAdvice");
  }

  public static class CoreConstructorSeedNodeAdvice {
    @Advice.OnMethodExit
    public static void afterConstruct(
        @Advice.Argument(2) final Set<SeedNode> seedNodes, @Advice.This final Core core) {
      InstrumentationContext.get(Core.class, String.class)
          .put(core, SeedNodeHelper.toStringForm(seedNodes));
    }

    public static void muzzleCheck(RequestSpan requestSpan) {
      requestSpan.status(RequestSpan.StatusCode.ERROR);
    }
  }

  public static class CoreConstructorConnectionStringAdvice {
    @Advice.OnMethodExit
    public static void afterConstruct(
        @Advice.Argument(2) final ConnectionString connectionString, @Advice.This final Core core) {
      InstrumentationContext.get(Core.class, String.class)
          .put(core, ConnectionStringHelper.toHostPortList(connectionString));
    }
  }
}
