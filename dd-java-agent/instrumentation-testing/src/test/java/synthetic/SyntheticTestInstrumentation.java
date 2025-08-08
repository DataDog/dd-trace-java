package synthetic;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class SyntheticTestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SyntheticTestInstrumentation() {
    super("synthetic-test");
  }

  @Override
  public String instrumentedType() {
    return getClass().getName() + "$WithSynthetic";
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("access$000"), getClass().getName() + "$AccessAdvice");
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
