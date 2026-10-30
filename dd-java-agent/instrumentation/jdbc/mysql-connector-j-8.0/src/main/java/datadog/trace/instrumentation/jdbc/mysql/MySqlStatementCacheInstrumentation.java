package datadog.trace.instrumentation.jdbc.mysql;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Properties;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class MySqlStatementCacheInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MySqlStatementCacheInstrumentation() {
    super("mysql-stmt-cache");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && InstrumenterConfig.get().isMysqlStmtCacheEnabled();
  }

  @Override
  public String instrumentedType() {
    return "com.mysql.cj.jdbc.NonRegisteringDriver";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("connect"))
            .and(takesArguments(2))
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, Properties.class)),
        MySqlStatementCacheInstrumentation.class.getName() + "$ConnectAdvice");
  }

  public static class ConnectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final String url,
        @Advice.Argument(value = 1, readOnly = false) Properties info) {
      info = MySqlStatementCacheDefaults.withDefaults(url, info);
    }
  }
}
