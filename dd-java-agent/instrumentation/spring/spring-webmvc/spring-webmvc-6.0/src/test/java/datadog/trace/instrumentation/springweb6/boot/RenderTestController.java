package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Returns view names that {@link RenderTestConfig} resolves to view beans. */
@Controller
public class RenderTestController {
  @GetMapping("/render/named")
  public String named() {
    return RenderTestConfig.TEXT_VIEW;
  }

  @GetMapping("/render/failing")
  public String failing() {
    return RenderTestConfig.FAILING_VIEW;
  }
}
