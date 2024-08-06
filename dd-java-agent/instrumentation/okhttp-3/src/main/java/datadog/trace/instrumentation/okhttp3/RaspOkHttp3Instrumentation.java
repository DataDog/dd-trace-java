package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import net.bytebuddy.asm.Advice;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

@AutoService(InstrumenterModule.class)
public class RaspOkHttp3Instrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType {

  public RaspOkHttp3Instrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String instrumentedType() {
    return "okhttp3.OkHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RaspInterceptor",
      InstrumentationLogger.class.getName(),
      NetworkConnectionModule.class.getName()
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, named("okhttp3.OkHttpClient$Builder"))),
        RaspOkHttp3Instrumentation.class.getName() + "$OkHttp3ClientAdvice");
  }

  public static class OkHttp3ClientAdvice {
    @Advice.OnMethodEnter()
    public static void addRaspInterceptor(@Advice.Argument(0) final OkHttpClient.Builder builder) {
      for (final Interceptor interceptor : builder.interceptors()) {
        if (interceptor instanceof RaspInterceptor) {
          return;
        }
      }
      builder.addInterceptor(new RaspInterceptor());
    }
  }
}
