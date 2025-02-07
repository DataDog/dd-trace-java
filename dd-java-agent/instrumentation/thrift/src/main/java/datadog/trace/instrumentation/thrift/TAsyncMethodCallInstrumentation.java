package datadog.trace.instrumentation.thrift;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.thrift.ThriftConstants.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.thrift.ThriftConstants.T_ASYNC_METHOD_CALL;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class TAsyncMethodCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public TAsyncMethodCallInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public String hierarchyMarkerType() {
    return T_ASYNC_METHOD_CALL;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ThriftConstants",
      packageName + ".ThriftBaseDecorator",
      packageName + ".ThriftClientDecorator",
      packageName + ".ThriftConstants$Tags",
      packageName + ".AbstractContext",
      packageName + ".AsyncContext",
      packageName + ".Context",
      packageName + ".ClientOutProtocolWrapper",
      packageName + ".AsyncMethodCallConstructorAdvice",
      packageName + ".AsyncMethodCallMethodAdvice",
      packageName + ".DataDogAsyncMethodCallback",
      packageName + ".InjectAdepter",
      packageName + ".CTProtocolFactory"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(isConstructor(), packageName + ".AsyncMethodCallConstructorAdvice");
    transformation.applyAdvice(
        isMethod().and(isProtected()).and(named("prepareMethodCall")),
        packageName + ".AsyncMethodCallMethodAdvice");
  }
}
