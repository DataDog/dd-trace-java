package datadog.trace.instrumentation.reactivestreams;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This instrumentation provides support for asynchronous type results.
 *
 * @see datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator
 */
@AutoService(Instrumenter.class)
public class ReactiveStreamsInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ReactiveStreamsInstrumentation() {
    super("reactive-streams", "reactive-streams-1");
  }

  @Override
  protected boolean defaultEnabled() {
    // Only used with OpenTelemetry @WithSpan annotations
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.reactivestreams.Publisher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("org.reactivestreams.Publisher"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      this.packageName + ".ReactiveStreamsAsyncResultSupportExtension",
      this.packageName + ".ReactiveStreamsAsyncResultSupportExtension$WrappedPublisher",
      this.packageName + ".ReactiveStreamsAsyncResultSupportExtension$WrappedSubscriber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), this.getClass().getName() + "$PublisherAdvice");
  }

  public static class PublisherAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void init() {
      ReactiveStreamsAsyncResultSupportExtension.initialize();
    }
  }
}
