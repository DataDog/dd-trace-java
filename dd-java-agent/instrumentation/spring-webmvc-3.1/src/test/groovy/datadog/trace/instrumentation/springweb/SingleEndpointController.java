package datadog.trace.instrumentation.springweb;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/single")
public class SingleEndpointController {
  @GetMapping
  public @ResponseBody String get() {
    return "Hello World";
  }
}
