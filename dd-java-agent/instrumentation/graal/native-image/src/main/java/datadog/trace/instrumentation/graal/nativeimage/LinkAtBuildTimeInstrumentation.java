package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class LinkAtBuildTimeInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.LinkAtBuildTimeSupport";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("linkAtBuildTime")).and(takesArgument(0, Class.class)),
        LinkAtBuildTimeInstrumentation.class.getName() + "$LinkAtBuildTimeAdvice");
  }

  public static class LinkAtBuildTimeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) Class<?> declaringClass,
        @Advice.Return(readOnly = false) boolean linkAtBuildTime) {
      // skip AndroidPlatform from build-time linking because we're not building on Android
      if ("datadog.okhttp3.internal.platform.AndroidPlatform".equals(declaringClass.getName())) {
        linkAtBuildTime = false;
      }
    }
  }
}
