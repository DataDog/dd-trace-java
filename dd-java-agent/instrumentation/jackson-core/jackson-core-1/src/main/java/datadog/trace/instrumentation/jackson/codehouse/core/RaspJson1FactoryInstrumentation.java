package datadog.trace.instrumentation.jackson.codehouse.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;

@AutoService(InstrumenterModule.class)
public class RaspJson1FactoryInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType {

  public RaspJson1FactoryInstrumentation() {
    super("jackson", "jackson-1");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {InstrumentationLogger.class.getName()};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createJsonParser")
            .and(isMethod())
            .and(
                takesArguments(String.class)
                    .or(takesArguments(InputStream.class))
                    .or(takesArguments(Reader.class))
                    .or(takesArguments(URL.class))
                    .or(takesArguments(byte[].class))),
        "datadog.trace.instrumentation.appsec.rasp.advices.NetworkConnectionRaspAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.codehaus.jackson.JsonFactory";
  }
}
