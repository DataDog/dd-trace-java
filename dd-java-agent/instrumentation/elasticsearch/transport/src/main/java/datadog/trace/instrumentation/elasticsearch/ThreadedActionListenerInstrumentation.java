package datadog.trace.instrumentation.elasticsearch;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.elasticsearch.action.support.ThreadedActionListener;

/**
 * Captures context at the point a request is made, and ensures it propagates into asynchronous
 * actions.
 */
@AutoService(Instrumenter.class)
public final class ThreadedActionListenerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ThreadedActionListenerInstrumentation() {
    super("elasticsearch", "elasticsearch-transport");
  }

  @Override
  public String instrumentedType() {
    return "org.elasticsearch.action.support.ThreadedActionListener";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.elasticsearch.action.support.ThreadedActionListener", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // only one constructor
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformation.applyAdvice(
        namedOneOf("onResponse", "onFailure").and(takesArguments(1)),
        getClass().getName() + "$OnResponse");
  }

  @SuppressWarnings("rawtypes")
  public static final class Construct {
    @Advice.OnMethodExit
    public static void after(@Advice.This ThreadedActionListener listener) {
      capture(
          InstrumentationContext.get(ThreadedActionListener.class, State.class), listener, true);
    }
  }

  @SuppressWarnings("rawtypes")
  public static final class OnResponse {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ThreadedActionListener listener) {
      return startTaskScope(
          InstrumentationContext.get(ThreadedActionListener.class, State.class), listener);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
