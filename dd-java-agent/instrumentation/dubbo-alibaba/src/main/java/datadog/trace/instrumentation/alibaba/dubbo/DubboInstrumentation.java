package datadog.trace.instrumentation.alibaba.dubbo;


import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class DubboInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public DubboInstrumentation() {
    super("alibaba-dubbo");
  }

  public static final String CLASS_NAME = "com.alibaba.dubbo.monitor.support.MonitorFilter";

  @Override
  public String hierarchyMarkerType() {
    return CLASS_NAME;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(CLASS_NAME));
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("invoke"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("com.alibaba.dubbo.rpc.Invoker")))
            .and(takesArgument(1, named("com.alibaba.dubbo.rpc.Invocation"))),
        packageName + ".RequestAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".DubboDecorator",
        packageName + ".DubboConstants",
        packageName + ".RequestAdvice",
        packageName + ".DubboHeadersExtractAdapter",
        packageName + ".DubboHeadersInjectAdapter"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.alibaba.dubbo.rpc.RpcContext", AgentSpan.class.getName());
  }
}
