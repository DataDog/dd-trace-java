package dd.trace.instrumentation.springwebflux.server;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Accepts cross-origin requests from {@link #ALLOWED_ORIGIN}, so preflight requests succeed. */
@RestController
public class CorsController {
  public static final String ALLOWED_ORIGIN = "http://example.com";

  @CrossOrigin(origins = ALLOWED_ORIGIN)
  @GetMapping("/cors/{id}")
  public String cors(@PathVariable("id") long id) {
    return "cors " + id;
  }
}
