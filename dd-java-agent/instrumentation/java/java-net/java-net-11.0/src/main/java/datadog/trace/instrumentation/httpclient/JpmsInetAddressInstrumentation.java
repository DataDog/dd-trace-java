package datadog.trace.instrumentation.httpclient;

import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.JavaModuleOpenProvider;
import java.util.Collection;

@AutoService(InstrumenterModule.class)
public class JpmsInetAddressInstrumentation extends InstrumenterModule
    implements JavaModuleOpenProvider {

  public JpmsInetAddressInstrumentation() {
    super("java-net");
  }

  @Override
  public Collection<String> triggerClasses() {
    return singleton("java.net.InetAddress");
  }
}
