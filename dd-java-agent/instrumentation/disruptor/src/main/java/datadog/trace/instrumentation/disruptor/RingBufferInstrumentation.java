package datadog.trace.instrumentation.disruptor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

@AutoService(InstrumenterModule.class)
public class RingBufferInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RingBufferInstrumentation() {
    super("disruptor");
  }

  public static final String CLASS_NAME = "com.lmax.disruptor.Sequencer";

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher hierarchyMatcher() {
    return implementsInterface(named(CLASS_NAME));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("publish")),
        packageName + ".RingBufferPublishAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".RingBufferPublishAdvice",
        packageName + ".DisruptorDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        Long.class.getName(), AgentSpan.class.getName());
  }
}
