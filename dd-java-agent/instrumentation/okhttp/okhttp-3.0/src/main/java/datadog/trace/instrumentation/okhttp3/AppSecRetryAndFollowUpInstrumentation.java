package datadog.trace.instrumentation.okhttp3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reports HTTP redirects followed by OkHttp 3.4+ (including 4.x) to AppSec, where the redirect loop
 * lives in {@code RetryAndFollowUpInterceptor}. OkHttp 3.0-3.3 is covered by {@link
 * AppSecHttpEngineInstrumentation}.
 */
@AutoService(InstrumenterModule.class)
public class AppSecRetryAndFollowUpInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AppSecRetryAndFollowUpInstrumentation() {
    super("okhttp", "okhttp-3");
  }

  @Override
  public String instrumentedType() {
    return "okhttp3.internal.http.RetryAndFollowUpInterceptor";
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    // the second parameter differs across versions: none (3.4-3.9), Route (3.10+, 4.0) or Exchange
    transformer.applyAdvice(
        isMethod()
            .and(named("followUpRequest"))
            .and(takesArgument(0, named("okhttp3.Response")))
            .and(returns(named("okhttp3.Request"))),
        AppSecRetryAndFollowUpInstrumentation.class.getName() + "$FollowUpRequestAdvice");
  }

  public static class FollowUpRequestAdvice {
    // no suppress: a BlockingException must reach the caller to block the redirect
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodExit
    public static void onFollowUpRequest(
        @Advice.This final Object interceptor,
        @Advice.Argument(0) final Response userResponse,
        @Advice.Return final Request followUp) {
      // only redirects that OkHttp is going to follow (it also builds follow-ups for auth/retries).
      // Accepted false positive: this runs before OkHttp's own checks on the follow-up (one-shot
      // request bodies, max follow-ups limit), so a redirect may be reported that OkHttp then
      // drops.
      if (followUp == null || userResponse.code() < 300 || userResponse.code() >= 400) {
        return;
      }
      try {
        AppSecInterceptor.onRedirect(userResponse, followUp);
      } catch (final BlockingException e) {
        // OkHttp only cleans up the redirect after followUpRequest returns
        AppSecInterceptor.onBlockedRedirect(interceptor, userResponse);
        throw e;
      }
    }
  }
}
