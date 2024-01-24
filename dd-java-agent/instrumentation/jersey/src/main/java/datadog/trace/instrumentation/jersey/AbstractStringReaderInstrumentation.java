package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class AbstractStringReaderInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes {

  public AbstractStringReaderInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fromString").and(isPublic().and(takesArguments(String.class))),
        packageName + ".AbstractStringReaderAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.glassfish.jersey.internal.inject.ParamConverters$AbstractStringReader",
      "org.glassfish.jersey.server.internal.inject.ParamConverters$AbstractStringReader"
    };
  }
}
