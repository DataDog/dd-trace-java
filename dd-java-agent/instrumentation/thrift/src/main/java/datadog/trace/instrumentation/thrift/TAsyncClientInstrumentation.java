package datadog.trace.instrumentation.thrift;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.thrift.ThriftConstants.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.thrift.ThriftConstants.TASYNC_CLIENT;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class TAsyncClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public TAsyncClientInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String hierarchyMarkerType() {
    return TASYNC_CLIENT;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
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
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(isConstructor()
        ,packageName + ".TAsyncClientConstructorAdvice");
  }
}
