package datadog.trace.instrumentation.gradle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;

@AutoService(Instrumenter.class)
public class GradleTestResultProcessorImplementation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public GradleTestResultProcessorImplementation() {
    super("gradle-test-result-processor");
  }

  @Override
  public String instrumentedType() {
    return "org.gradle.api.internal.tasks.testing.processors.CaptureTestOutputTestResultProcessor";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".GradleTestResultListener"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("started")
            .and(
                takesArgument(
                    0, named("org.gradle.api.internal.tasks.testing.TestDescriptorInternal")))
            .and(takesArgument(1, named("org.gradle.api.internal.tasks.testing.TestStartEvent"))),
        GradleTestResultProcessorImplementation.class.getName() + "$StartedAdvice");

    transformation.applyAdvice(
        named("completed")
            .and(takesArgument(0, named("java.lang.Object")))
            .and(
                takesArgument(1, named("org.gradle.api.internal.tasks.testing.TestCompleteEvent"))),
        GradleTestResultProcessorImplementation.class.getName() + "$CompletedAdvice");
  }

  public static class StartedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onStarted(@Advice.Argument(0) final TestDescriptorInternal testDescriptor) {
      GradleTestResultListener.INSTANCE.onTestStarted(testDescriptor.getId());
    }
  }

  public static class CompletedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onFinished(@Advice.Argument(0) final Object testId) {
      GradleTestResultListener.INSTANCE.onTestFinished(testId);
    }
  }
}
