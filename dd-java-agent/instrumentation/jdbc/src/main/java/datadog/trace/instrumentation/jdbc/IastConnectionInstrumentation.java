package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.ConnectionInstrumentation.CONCRETE_TYPES;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.iast.InstrumentationBridge;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IastConnectionInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.ForConfiguredType {
  public IastConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        IastConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
  }

  @Override
  public String configuredMatchingType() {
    // this won't match any class unless the property is set
    return Config.get().getJdbcConnectionClassName();
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }

  public static class ConnectionPrepareAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void before(@Advice.Argument(0) final String sql) {
      InstrumentationBridge.onJdbcQuery(sql);
    }
  }
}
