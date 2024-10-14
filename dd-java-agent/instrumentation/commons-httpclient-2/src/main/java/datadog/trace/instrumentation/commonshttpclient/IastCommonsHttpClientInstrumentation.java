package datadog.trace.instrumentation.commonshttpclient;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class IastCommonsHttpClientInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public IastCommonsHttpClientInstrumentation() {
    super("commons-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.commons.httpclient.HttpClient";
  }

  @Override
  public String muzzleDirective() {
    return "commons-http-client-x";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    //    transformer.applyAdvice(
    //        isMethod()
    //            .and(named("executeMethod"))
    //            .and(takesArguments(3))
    //            .and(takesArgument(1, named("org.apache.commons.httpclient.HttpMethod"))),
    //        IastCommonsHttpClientInstrumentation.class.getName() + "$ExecAdvice");
  }

  //  public static class ExecAdvice {
  //    @Advice.OnMethodEnter(suppress = Throwable.class)
  //    @Sink(VulnerabilityTypes.SSRF)
  //    public static void methodEnter(@Advice.Argument(1) final HttpMethod httpMethod) {
  //      final SsrfModule module = InstrumentationBridge.SSRF;
  //      if (module != null) {
  //        module.onURLConnection(httpMethod);
  //      }
  //    }
  //  }
}
