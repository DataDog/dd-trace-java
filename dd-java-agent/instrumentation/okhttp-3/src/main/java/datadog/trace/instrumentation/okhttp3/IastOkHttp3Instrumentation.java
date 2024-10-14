package datadog.trace.instrumentation.okhttp3;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class IastOkHttp3Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public IastOkHttp3Instrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String instrumentedType() {
    return "okhttp3.OkHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".IastInterceptor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    //    transformer.applyAdvice(
    //        isConstructor().and(takesArgument(0, named("okhttp3.OkHttpClient$Builder"))),
    //        IastOkHttp3Instrumentation.class.getName() + "$OkHttp3ClientAdvice");
  }

  //  public static class OkHttp3ClientAdvice {
  //    @Advice.OnMethodEnter(suppress = Throwable.class)
  //    @Sink(VulnerabilityTypes.SSRF)
  //    public static void addIastInterceptor(@Advice.Argument(0) final OkHttpClient.Builder
  // builder) {
  //      for (final Interceptor interceptor : builder.interceptors()) {
  //        if (interceptor instanceof IastInterceptor) {
  //          return;
  //        }
  //      }
  //      builder.addInterceptor(new IastInterceptor());
  //    }
  //  }
}
