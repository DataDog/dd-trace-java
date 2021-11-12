package synthetic;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SyntheticTestInstrumentation extends Instrumenter.Tracing {

  public SyntheticTestInstrumentation() {
    super("synthetic-test");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named(getClass().getName() + "$WithSynthetic");
  }

  @Override
  public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
    if (Boolean.getBoolean("synthetic.test.enabled")) {
      return none();
    } else {
      return super.methodIgnoreMatcher();
    }
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("access$000"), getClass().getName() + "$AccessAdvice");
  }

  public static class Compute {
    public static Integer result(Integer i) {
      WithSynthetic p = new WithSynthetic(i);
      return p.secret + 1;
    }
  }

  private static final class WithSynthetic {
    private Integer secret;

    public WithSynthetic(Integer secret) {
      this.secret = secret;
    }
  }

  public static final class AccessAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Return(readOnly = false) Integer i) {
      i = i * 2;
    }
  }
}
