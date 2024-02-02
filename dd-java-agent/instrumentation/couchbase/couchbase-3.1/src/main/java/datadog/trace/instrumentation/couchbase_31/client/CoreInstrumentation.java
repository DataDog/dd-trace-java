package datadog.trace.instrumentation.couchbase_31.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.env.SeedNode;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CoreInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final Reference TRACING_IDENTIFIERS_REFERENCE =
      new Reference.Builder("com.couchbase.client.core.cnc.TracingIdentifiers").build();

  private static final Reference SUSPICIOUS_EXPIRY_REFERENCE =
      new Reference.Builder(
              "com.couchbase.client.core.cnc.events.request.SuspiciousExpiryDurationEvent")
          .build();

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
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {TRACING_IDENTIFIERS_REFERENCE, SUSPICIOUS_EXPIRY_REFERENCE};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SeedNodeHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(2, named("java.util.Set"))),
        CoreInstrumentation.class.getName() + "$CoreConstructorAdvice");
  }

  public static class CoreConstructorAdvice {
    @Advice.OnMethodExit
    public static void afterConstruct(
        @Advice.Argument(2) final Set<SeedNode> seedNodes, @Advice.This final Core core) {
      InstrumentationContext.get(Core.class, String.class)
          .put(core, SeedNodeHelper.toStringForm(seedNodes));
    }
  }
}
