package datadog.trace.instrumentation.okhttp2;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class RaspOkHttp2Instrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType {

  public RaspOkHttp2Instrumentation() {
    super("okhttp", "okhttp-2");
  }

  @Override
  public String instrumentedType() {
    return "com.squareup.okhttp.OkHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      InstrumentationLogger.class.getName(),
      NetworkConnectionModule.class.getName(),
      packageName + ".RaspInterceptor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), RaspOkHttp2Instrumentation.class.getName() + "$OkHttp2ClientAdvice");
  }

  public static class OkHttp2ClientAdvice {
    @Advice.OnMethodExit()
    public static void addRaspInterceptor(@Advice.This final OkHttpClient client) {
      for (final Interceptor interceptor : client.interceptors()) {
        if (interceptor instanceof RaspInterceptor) {
          return;
        }
      }
      client.interceptors().add(new RaspInterceptor());
    }
  }
}
