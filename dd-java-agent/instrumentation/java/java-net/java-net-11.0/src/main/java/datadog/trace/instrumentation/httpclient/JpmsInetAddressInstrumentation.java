package datadog.trace.instrumentation.httpclient;

import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.JavaModuleOpenProvider;
import java.util.Collection;

@AutoService(InstrumenterModule.class)
public class JpmsInetAddressInstrumentation extends InstrumenterModule
    implements JavaModuleOpenProvider {

  private static final Collection<String> TRIGGER_CLASSES = singleton("java.net.InetAddress");

  public JpmsInetAddressInstrumentation() {
    super("java-net");
  }

  @Override
  public Collection<String> triggerClasses() {
    return TRIGGER_CLASSES;
  }
}
