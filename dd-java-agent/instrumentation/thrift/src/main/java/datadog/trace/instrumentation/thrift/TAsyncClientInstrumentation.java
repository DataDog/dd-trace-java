package datadog.trace.instrumentation.thrift;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.thrift.ThriftConstants.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.thrift.ThriftConstants.TASYNC_CLIENT;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

@AutoService(Instrumenter.class)
public class TAsyncClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public TAsyncClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(TASYNC_CLIENT);
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ThriftConstants",
        packageName + ".ThriftBaseDecorator",
        packageName + ".ThriftClientDecorator",
        packageName + ".ThriftConstants$Tags",
        packageName + ".AbstractContext",
        packageName + ".ClientOutProtocolWrapper",
        packageName + ".TAsyncClientConstructorAdvice",
        packageName + ".InjectAdepter",
        packageName + ".CTProtocolFactory",
        packageName + ".Context"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor()
        ,packageName + ".TAsyncClientConstructorAdvice");
  }
}
