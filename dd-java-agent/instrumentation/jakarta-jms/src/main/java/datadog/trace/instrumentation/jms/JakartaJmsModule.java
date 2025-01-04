package datadog.trace.instrumentation.jms;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.function.Function;

@AutoService(InstrumenterModule.class)
public class JakartaJmsModule extends JavaxJmsModule {

  public JakartaJmsModule() {
    super("jakarta", "jakarta-jms");
  }

  public Function<String, String> adviceShading() {
    return name -> {
      if (name.startsWith("javax")) {
        return "jakarta" + name.substring(5);
      }
      return name;
    };
  }
}
