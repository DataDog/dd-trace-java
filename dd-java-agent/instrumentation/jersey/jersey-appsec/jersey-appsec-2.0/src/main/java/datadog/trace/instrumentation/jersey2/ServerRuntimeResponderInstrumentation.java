package datadog.trace.instrumentation.jersey2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

// tested in GrizzlyTest (grizzly-http)
@AutoService(InstrumenterModule.class)
public class ServerRuntimeResponderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ServerRuntimeResponderInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "jersey_2+3";
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.ServerRuntime$Responder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("process"))
            .and(takesArguments(1))
            .and(takesArgument(0, Throwable.class))
            .and(not(isStatic())),
        ServerRuntimeResponderInstrumentation.class.getName() + "$ProcessAdvice");
  }

  static class ProcessAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    static boolean /* skip */ before(@Advice.Argument(0) Throwable t_) {
      Throwable t = t_;
      if (!(t instanceof BlockingException)) {
        if (!(t.getCause() instanceof BlockingException)) {
          return false;
        }
        t = t.getCause();
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return true;
      }
      agentSpan.addThrowable(t);
      return true; // skip body to avoid trying to handle the error
    }
  }
}
