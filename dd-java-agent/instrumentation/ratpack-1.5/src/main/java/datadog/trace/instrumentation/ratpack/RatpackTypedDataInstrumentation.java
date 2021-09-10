package datadog.trace.instrumentation.ratpack;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RatpackTypedDataInstrumentation extends Instrumenter.AppSec {
  public RatpackTypedDataInstrumentation() {
    super("ratpack-request-body");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("ratpack.http.internal.ByteBufBackedTypedData");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "ratpack.http.internal.ByteBufBackedTypedData", Boolean.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GetTextCharSequenceSupplier",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getBuffer")
            .and(takesArguments(0))
            .or(named("getBytes").and(takesArguments(0)))
            .or(named("writeTo").and(takesArguments(OutputStream.class)))
            .or(named("getInputStream").and(takesArguments(0))),
        packageName + ".RatpackRequestBodyCallGetTextAdvice");
    transformation.applyAdvice(
        named("getText").and(takesArguments(0).or(takesArguments(Charset.class))),
        packageName + ".RatpackRequestBodyGetTextCalledAdvice");
  }
}
