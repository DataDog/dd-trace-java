package datadog.trace.instrumentation.cassandra4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.cassandra4.CassandraClientDecorator.DECORATE;
import static datadog.trace.instrumentation.cassandra4.CassandraClientDecorator.JAVA_CASSANDRA;
import static datadog.trace.instrumentation.cassandra4.CassandraClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;

/**
 * Instruments {@code DefaultSession.execute(Request, GenericType)} which is the single concrete
 * dispatch method that all CQL operations (sync execute, async executeAsync, prepare, etc.) flow
 * through. The default interface methods on {@code CqlSession} (execute, executeAsync) all delegate
 * to this method, and it is concretely declared on {@code DefaultSession}, making it compatible
 * with the simple method graph compiler used by default.
 */
public class CqlSessionExecuteInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.datastax.oss.driver.internal.core.session.DefaultSession";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("com.datastax.oss.driver.api.core.session.Request")))
            .and(
                takesArgument(
                    1, named("com.datastax.oss.driver.api.core.type.reflect.GenericType"))),
        CqlSessionExecuteInstrumentation.class.getName() + "$CqlSessionExecuteAdvice");
  }

  public static class CqlSessionExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final CqlSession session,
        @Advice.Argument(value = 0, readOnly = false) Request request,
        @Advice.Argument(1) final GenericType<?> resultType) {
      if (CallDepthThreadLocalMap.incrementCallDepth(CqlSession.class) > 0) {
        return null;
      }
      // Only instrument CQL statement execution, not prepare requests or other request types
      if (!(request instanceof Statement)) {
        CallDepthThreadLocalMap.reset(CqlSession.class);
        return null;
      }
      final String query = ContactPointsUtil.getQuery((Statement<?>) request);
      final AgentSpan span = startSpan(JAVA_CASSANDRA, OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, session);
      DECORATE.onStatement(span, query);
      final String contactPoints = ContactPointsUtil.getContactPoints(session);
      if (contactPoints != null) {
        span.setTag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, contactPoints);
      }
      // DBM comment injection: inject trace context into CQL for SimpleStatement only
      if (request instanceof SimpleStatement) {
        final String dbName = ContactPointsUtil.getKeyspace(session);
        final String hostname = ContactPointsUtil.getFirstHost(session);
        final String injectedQuery = CassandraDBMUtil.injectComment(span, query, hostname, dbName);
        if (injectedQuery != null && !injectedQuery.equals(query)) {
          request = ((SimpleStatement) request).setQuery(injectedQuery);
        }
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(CqlSession.class);
      final AgentSpan span = scope.span();
      if (result instanceof CompletionStage) {
        // Async path: close the scope but let the callback finish the span
        scope.close();
        ((CompletionStage<?>) result).whenComplete(new SpanFinishingCallback(span));
      } else {
        // Sync path: finish span immediately
        try {
          if (throwable != null) {
            DECORATE.onError(span, throwable);
          }
          if (result instanceof ResultSet) {
            DECORATE.onResponse(span, (ResultSet) result);
          }
          DECORATE.beforeFinish(span);
        } finally {
          scope.close();
          span.finish();
        }
      }
    }
  }
}
