package test;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import net.bytebuddy.asm.Advice;
import org.jboss.modules.Module;

@AutoService(InstrumenterModule.class)
public class ModulePatchInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public ModulePatchInstrumentation() {
    super("jboss-module-patch");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.modules.Module";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("getResources"), getClass().getName() + "$SystemResourcesAdvice");
  }

  public static class SystemResourcesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This Module self,
        @Advice.Argument(0) String name,
        @Advice.Return(readOnly = false) Enumeration<URL> ret) {
      if (self.getName().endsWith(".jdk")) {
        ret = Collections.emptyEnumeration();
      }
    }
  }
}
