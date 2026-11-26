package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import okhttp3.Request;
import okhttp3.Response;

@AutoService(InstrumenterModule.class)
public class AppSecHttpEngineInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AppSecHttpEngineInstrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String instrumentedType() {
    return "okhttp3.internal.http.HttpEngine";
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("sendRequest")).and(takesArguments(0)),
        AppSecHttpEngineInstrumentation.class.getName() + "$SendRequestAdvice");
  }

  public static class SendRequestAdvice {
    // no suppress: a BlockingException must reach the caller to block the redirect
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodEnter
    public static void onSendRequest(
        @Advice.FieldValue("priorResponse") final Response priorResponse,
        @Advice.FieldValue("userRequest") final Request userRequest) {
      // only redirects
      if (priorResponse == null || priorResponse.code() < 300 || priorResponse.code() >= 400) {
        return;
      }
      AppSecInterceptor.onRedirect(priorResponse, userRequest);
    }
  }
}
