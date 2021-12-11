package datadog.trace.instrumentation.jetty_util;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class QueuedThreadPoolInstrumentation extends Instrumenter.Tracing {
  public QueuedThreadPoolInstrumentation() {
    super("jetty-concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.util.thread.QueuedThreadPool");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(namedOneOf("dispatch", "execute"), getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      task = Wrapper.wrap(task);
    }
  }
}
