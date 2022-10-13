package datadog.trace.instrumentation.thrift;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.thrift.ThriftConstants.INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.thrift.ThriftConstants.T_ASYNC_METHOD_CALL;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class TAsyncMethodCallInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public TAsyncMethodCallInstrumentation() {
    super(INSTRUMENTATION_NAME);
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(T_ASYNC_METHOD_CALL));
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor()
        ,packageName+ ".AsyncMethodCallConstructorAdvice");
    transformation.applyAdvice(isMethod()
            .and(isProtected())
            .and(named("prepareMethodCall"))
        ,packageName + ".AsyncMethodCallMethodAdvice");
  }
}
