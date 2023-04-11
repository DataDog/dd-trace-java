package datadog.trace.instrumentation.dubbo_2_7x;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class DubboProviderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public DubboProviderInstrumentation() {
    super("apache-dubbo");
  }

  public static final String CLASS_NAME = "org.apache.dubbo.rpc.proxy.AbstractProxyInvoker";

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(CLASS_NAME));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("invoke"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.dubbo.rpc.Invocation"))),
        packageName + ".DubboProviderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".DubboDecorator",
        packageName + ".DubboTraceInfo",
        packageName + ".HostAndPort",
        packageName + ".DubboConstants",
        packageName + ".DubboProviderAdvice",
        packageName + ".DubboHeadersExtractAdapter",
        packageName + ".DubboHeadersInjectAdapter"
    };
  }

}
