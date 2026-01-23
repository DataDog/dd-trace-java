package datadog.trace.instrumentation.jms;

import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class JakartaJmsModule extends JavaxJmsModule {
  public JakartaJmsModule() {
    super("jakarta", "jakarta-jms", "jms");
  }

  @Override
  public String muzzleDirective() {
    return "jakarta.jms";
  }

  @Override
  public Map<String, String> adviceShading() {
    return singletonMap("javax", "jakarta");
  }
}
