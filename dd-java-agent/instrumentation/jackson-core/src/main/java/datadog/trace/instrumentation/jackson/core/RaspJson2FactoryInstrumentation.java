package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;

@AutoService(InstrumenterModule.class)
public class RaspJson2FactoryInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType {

  public RaspJson2FactoryInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {InstrumentationLogger.class.getName()};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createParser")
            .and(isMethod())
            .and(
                isPublic()
                    .and(
                        takesArguments(String.class)
                            .or(takesArguments(InputStream.class))
                            .or(takesArguments(Reader.class))
                            .or(takesArguments(URL.class))
                            .or(takesArguments(byte[].class)))),
        "datadog.trace.instrumentation.appsec.rasp.advices.NetworkConnectionRaspAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.core.JsonFactory";
  }
}
