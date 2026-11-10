package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Target of the servlet forward rendered by {@link RenderTestController#forward()}. */
@RestController
public class ForwardTargetController {
  public static final String PATH = "/render/forward-target";
  public static final String BODY = "forwarded";

  @GetMapping(PATH)
  public String target() {
    return BODY;
  }
}
