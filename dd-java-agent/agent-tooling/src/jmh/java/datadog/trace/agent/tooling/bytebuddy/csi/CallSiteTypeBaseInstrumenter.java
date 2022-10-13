package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.csi.Pointcut;
import java.util.Collections;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class CallSiteTypeBaseInstrumenter extends CallSiteInstrumenter
    implements ElementMatcher<TypeDescription> {

  private final String type;

  public CallSiteTypeBaseInstrumenter(final String type, final BaseCallSiteAdvice advice) {
    super(Collections.<CallSiteAdvice>singletonList(advice), type);
    this.type = type;
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return new ElementMatcher<ClassLoader>() {
      @Override
      public boolean matches(ClassLoader target) {
        return target != null;
      }
    };
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return this;
  }

  @Override
  public boolean matches(final TypeDescription target) {
    return "foo.bar.StringBuilderInsert".equals(target.getName());
  }

  @Override
  public boolean isEnabled() {
    return type.equals(System.getProperty("dd.benchmark.instrumentation", ""));
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return true;
  }

  protected abstract static class BaseCallSiteAdvice
      implements InvokeAdvice, CallSiteAdvice.HasHelpers, Pointcut {

    @Override
    public Pointcut pointcut() {
      return this;
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {CallSiteTypeBenchmarkHelper.class.getName()};
    }

    @Override
    public String type() {
      return "java/lang/StringBuilder";
    }

    @Override
    public String method() {
      return "insert";
    }

    @Override
    public String descriptor() {
      return "(I[C)Ljava/lang/StringBuilder;";
    }
  }
}
