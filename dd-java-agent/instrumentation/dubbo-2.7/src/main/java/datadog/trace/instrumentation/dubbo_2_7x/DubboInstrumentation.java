package datadog.trace.instrumentation.dubbo_2_7x;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class DubboInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public DubboInstrumentation() {
    super("apache-dubbo");
  }

//  public static final String CLASS_NAME = "org.apache.dubbo.rpc.Filter";
  public static final String CLASS_NAME = "org.apache.dubbo.monitor.support.MonitorFilter";

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(CLASS_NAME));
//    return implementsInterface(named(CLASS_NAME));
//    return implementsInterface(named(CLASS_NAME)).and(not(named("org.apache.dubbo.monitor.dubbo.MetricsFilter")));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("invoke"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.dubbo.rpc.Invoker")))
            .and(takesArgument(1, named("org.apache.dubbo.rpc.Invocation"))),
        packageName + ".RequestAdvice");
//    transformation.applyAdvice(
//        isMethod()
//            .and(isPublic())
//            .and(nameStartsWith("onResponse"))
//            .and(takesArguments(3))
//            .and(takesArgument(0, named("org.apache.dubbo.rpc.Result")))
//            .and(takesArgument(1, named("org.apache.dubbo.rpc.Invoker")))
//            .and(takesArgument(2, named("org.apache.dubbo.rpc.Invocation"))),
//        packageName + ".ResponseAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".DubboDecorator",
//        packageName + ".ResponseAdvice",
        packageName + ".RequestAdvice",
        packageName + ".DubboHeadersExtractAdapter",
        packageName + ".DubboHeadersInjectAdapter"
    };
  }

  @Override
  public Map<String, String> contextStore() {
//    return singletonMap("org.apache.dubbo.rpc.Invocation", AgentSpan.class.getName());
    return singletonMap("org.apache.dubbo.rpc.RpcContext", AgentSpan.class.getName());
  }
}
