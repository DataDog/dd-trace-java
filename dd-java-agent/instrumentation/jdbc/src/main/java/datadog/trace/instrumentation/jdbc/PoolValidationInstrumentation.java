package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/**
 * Stops connection pools' liveness checks from producing spans.
 *
 * <p>A pool checks that a connection is still usable by running a validation query, such as {@code
 * SELECT 1 FROM DUAL}, or by calling {@link java.sql.Connection#isValid}. Those checks run on
 * borrow, on return, and from keepalive timers, so they show up both inside application traces and
 * as standalone root spans. The advice runs each check under a black-hole span: nothing it executes
 * creates a span or injects trace context into the database.
 *
 * <p>Statements the pool runs on the application's behalf, such as connection init SQL, stay
 * traced.
 */
@AutoService(InstrumenterModule.class)
public final class PoolValidationInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String HIKARI_POOL_BASE = "com.zaxxer.hikari.pool.PoolBase";
  private static final String HIKARI_POOL_ELF = "com.zaxxer.hikari.pool.PoolElf";
  private static final String DBCP2_CONNECTION = "org.apache.commons.dbcp2.PoolableConnection";
  private static final String TOMCAT_DBCP2_CONNECTION =
      "org.apache.tomcat.dbcp.dbcp2.PoolableConnection";
  private static final String TOMCAT_JDBC_CONNECTION =
      "org.apache.tomcat.jdbc.pool.PooledConnection";
  private static final String C3P0_POOL_MANAGER =
      "com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool$1PooledConnectionResourcePoolManager";

  public PoolValidationInstrumentation() {
    super("jdbc", "jdbc-pool-validation");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      HIKARI_POOL_BASE, // HikariCP 2.4.1+
      HIKARI_POOL_ELF, // HikariCP 2.4.0
      DBCP2_CONNECTION, // standalone
      TOMCAT_DBCP2_CONNECTION, // bundled with Tomcat
      TOMCAT_JDBC_CONNECTION,
      C3P0_POOL_MANAGER
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String muteAdvice = PoolValidationInstrumentation.class.getName() + "$MuteValidationAdvice";
    // isConnectionAlive (isConnectionDead in 7.x) runs on borrow and from the keepalive timer;
    // checkValidationSupport runs the test query once when a connection is created
    transformer.applyAdvice(
        namedOneOf("isConnectionAlive", "isConnectionDead", "checkValidationSupport")
            .and(isDeclaredBy(namedOneOf(HIKARI_POOL_BASE, HIKARI_POOL_ELF))),
        muteAdvice);
    transformer.applyAdvice(
        named("validate").and(isDeclaredBy(namedOneOf(DBCP2_CONNECTION, TOMCAT_DBCP2_CONNECTION))),
        muteAdvice);
    transformer.applyAdvice(
        named("testPooledConnection").and(isDeclaredBy(named(C3P0_POOL_MANAGER))), muteAdvice);
    transformer.applyAdvice(
        named("validate")
            .and(takesArgument(0, int.class))
            .and(isDeclaredBy(named(TOMCAT_JDBC_CONNECTION))),
        PoolValidationInstrumentation.class.getName() + "$TomcatValidateAdvice");
  }

  public static class MuteValidationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter() {
      return activateSpan(blackholeSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  /**
   * Tomcat JDBC runs the user's init SQL through {@code validate(VALIDATE_INIT)}, so that action
   * stays traced, along with the test-on-connect check that shares it.
   */
  public static class TomcatValidateAdvice {
    /** {@code org.apache.tomcat.jdbc.pool.PooledConnection.VALIDATE_INIT} */
    public static final int VALIDATE_INIT = 4;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(@Advice.Argument(0) final int validateAction) {
      if (validateAction == VALIDATE_INIT) {
        return null;
      }
      return activateSpan(blackholeSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
