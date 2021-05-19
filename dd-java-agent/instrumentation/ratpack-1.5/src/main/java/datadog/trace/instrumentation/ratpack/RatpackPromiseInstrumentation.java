package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.google.common.net.HostAndPort;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import ratpack.exec.Upstream;
import ratpack.path.PathBinding;

@AutoService(Instrumenter.class)
public class RatpackPromiseInstrumentation extends Instrumenter.Tracing {

  public RatpackPromiseInstrumentation() {
    super("ratpack", "ratpack-promise");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("ratpack.exec.internal.DefaultPromise");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".UpstreamWrapper", packageName + ".DownstreamWrapper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, named("ratpack.exec.Upstream"))),
        RatpackPromiseInstrumentation.class.getName() + "$WrapUpstreamAdvice");
  }

  // Wrap the upstream rather than instrument because they're generally lambdas
  public static class WrapUpstreamAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onConstruct(
        @Advice.Argument(value = 0, readOnly = false) Upstream upstream) {
      upstream = UpstreamWrapper.wrapIfNeeded(upstream);
    }

    // To ensure consistent matching.
    public void muzzleCheck(final PathBinding binding, final HostAndPort host) {
      // This was added in 1.4.  Added here to ensure consistency with other instrumentation.
      binding.getDescription();

      // This is available in Guava 20 which was required starting in 1.5
      host.getHost();
    }
  }
}
