package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

@AutoService(Instrumenter.class)
public class RatpackTypedDataInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public RatpackTypedDataInstrumentation() {
    super("ratpack-request-body");
  }

  @Override
  public String instrumentedType() {
    return "ratpack.http.internal.ByteBufBackedTypedData";
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getBuffer")
            .and(takesArguments(0))
            .or(named("getBytes").and(takesArguments(0)))
            .or(named("writeTo").and(takesArguments(OutputStream.class)))
            .or(named("getInputStream").and(takesArguments(0))),
        packageName + ".RatpackRequestBodyCallGetBufferAdvice");
    transformer.applyAdvice(
        named("getText").and(takesArguments(0).or(takesArguments(Charset.class))),
        packageName + ".RatpackRequestBodyGetTextCalledAdvice");
  }
}
