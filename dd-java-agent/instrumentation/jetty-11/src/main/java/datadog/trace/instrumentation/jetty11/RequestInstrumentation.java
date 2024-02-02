package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class RequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public RequestInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setContext")
            .and(takesArgument(0, named("org.eclipse.jetty.server.handler.ContextHandler$Context")))
            .and(takesArgument(1, String.class)),
        packageName + ".SetContextPathAdvice");
  }
}
