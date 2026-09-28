package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JettyWebSocketUpgradeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public JettyWebSocketUpgradeInstrumentation() {
    super("jetty-client");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".JettyClientDecorator"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.Request", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("upgrade")
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.eclipse.jetty.client.Response")))
            .and(takesArgument(1, named("org.eclipse.jetty.io.EndPoint"))),
        packageName + ".WebSocketUpgradeAdvice");
  }
}
