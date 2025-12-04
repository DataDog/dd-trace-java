package datadog.trace.instrumentation.dubbo_2_7x;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class DubboFilterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy , Instrumenter.HasMethodAdvice{

  public DubboFilterInstrumentation() {
    super("apache-dubbo-filter","apache-dubbo-filter");
  }

//  public static final String CLASS_NAME = "org.apache.dubbo.monitor.support.MonitorFilter";
  public static final String CLASS_NAME = "org.apache.dubbo.rpc.Filter";
//  @Override
//  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
//    return ClassLoaderMatchers.hasClassNamed("org.apache.dubbo.rpc.Filter");
//  }

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
//    return extendsClass(named(CLASS_NAME));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("invoke"))
            .and(takesArgument(1, named("org.apache.dubbo.rpc.Invocation"))),
        packageName + ".DubboInvokeAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".DubboDecorator",
        packageName + ".DubboTraceInfo",
        packageName + ".HostAndPort",
        packageName + ".DubboConstants",
        packageName + ".DubboConsumerAdvice",
        packageName + ".DubboHeadersExtractAdapter",
        packageName + ".DubboHeadersInjectAdapter",
        packageName + ".DubboInvokeAdvice"
    };
  }
}
