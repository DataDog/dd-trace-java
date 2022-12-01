package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class ClassInitializationFeatureInstrumentation
    extends AbstractNativeImageInstrumentation implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "com.oracle.svm.hosted.classinitialization.ClassInitializationFeature";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("processClassInitializationOptions"))
            .and(
                takesArgument(
                    0,
                    named("com.oracle.svm.hosted.classinitialization.ClassInitializationSupport"))),
        packageName + ".ClassInitializationAdvice");
  }
}
