package datadog.trace.instrumentation.httpclient;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class JpmsInetAddressInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.HasMethodAdvice, Instrumenter.ForSingleType {

  public JpmsInetAddressInstrumentation() {
    super("java-net");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && JavaVirtualMachine.isJavaVersionAtLeast(9);
  }

  @Override
  public String instrumentedType() {
    return "java.net.InetAddress";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // it does not work with typeInitializer()
    transformer.applyAdvice(isConstructor(), packageName + ".JpmsInetAddressClearanceAdvice");
  }
}
