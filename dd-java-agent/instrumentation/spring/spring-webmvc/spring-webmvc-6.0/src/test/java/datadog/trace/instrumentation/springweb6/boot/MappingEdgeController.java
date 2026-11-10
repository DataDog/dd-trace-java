package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declares a GET-only mapping. Other methods are answered during handler lookup: OPTIONS by
 * Spring's internal {@code HttpOptionsHandler}, anything else with 405.
 */
@RestController
public class MappingEdgeController {
  public static final String GET_ONLY_PATH = "/mapping/get-only";

  @GetMapping(GET_ONLY_PATH)
  public String getOnly() {
    return "get only";
  }
}
