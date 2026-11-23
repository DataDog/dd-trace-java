package datadog.trace.instrumentation.springweb6.normalizedroute;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** PathPatternParser routes of {@link NormalizedRouteAppConfig}. */
@RestController
public class NormalizedRouteController {

  @GetMapping("/regex/{id:\\d+}")
  public String regex() {
    return "ok";
  }

  @GetMapping("/nested/{code:[0-9]{3}}")
  public String nestedRegex() {
    return "ok";
  }

  @GetMapping("/files/*.txt")
  public String singleStar() {
    return "ok";
  }

  @GetMapping("/static/**")
  public String trailingDoubleStar() {
    return "ok";
  }

  @GetMapping("/mixed/{id}/**")
  public String namedAndDoubleStar() {
    return "ok";
  }

  @GetMapping("/star/*/**")
  public String starAndDoubleStar() {
    return "ok";
  }

  @GetMapping("/rest/{*rest}")
  public String captureRest() {
    return "ok";
  }
}
