package test;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.jboss.modules.Module;

/**
 * This instrumentation is to hack the way the jboss module classloader is loading SPI services. In
 * fact, in test we have a classloader different from the one usually used when launching wildfly.
 * In particular, we do not want to have SPI load services defined outside the jboss classloader
 * module, otherwise this class won't be found afterwards.
 */
@AutoService(InstrumenterModule.class)
public class ModulePatchInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      if ("META-INF/services/javax.servlet.ServletContainerInitializer".equals(name)) {
        final List<URL> list = new ArrayList<>();
        while (ret.hasMoreElements()) {
          URL u = ret.nextElement();
          if (!u.toString().contains("logback-classic")) {
            list.add(u);
          }
        }
        ret = Collections.enumeration(list);
      }
    }
  }
}
