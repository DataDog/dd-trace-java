package datadog.trace.instrumentation.httpclient;

import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.JavaModuleOpenProvider;

@AutoService(InstrumenterModule.class)
public class JpmsInetAddressInstrumentation extends InstrumenterModule
    implements JavaModuleOpenProvider {

  public JpmsInetAddressInstrumentation() {
    super("java-net");
  }

  @Override
  public Iterable<String> triggerClasses() {
    return singleton("java.net.InetAddress");
  }
}
