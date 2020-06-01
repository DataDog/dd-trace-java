package datadog.trace.instrumentation.phantom;

import com.datastax.driver.core.Session;
import com.google.auto.service.AutoService;
import com.outworkers.phantom.ResultSet;
import com.outworkers.phantom.ops.QueryContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import scala.runtime.AbstractFunction1;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import scala.util.Try;

import java.util.Collections;
import java.util.Map;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

@Slf4j
@AutoService(Instrumenter.class)
public class PhantomInstrumentation extends Instrumenter.Default {
  public PhantomInstrumentation() {
    super("phantom", "scala-phantom");
  }


  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.outworkers.phantom.ops.QueryContext$RootQueryOps");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
      isMethod().and(ElementMatchers.<MethodDescription>named("future")), packageName + ".PhantomAdvice"
    );
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
      packageName + ".FutureCompletionListener",
      packageName + ".PhantomDecorator"
    };
  }
}
