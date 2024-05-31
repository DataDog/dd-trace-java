package datadog.smoketest.asmstandalonebilling;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/rest-api")
public class Controller {

  @GetMapping("/greetings")
  public String greetings(@RequestParam(name = "forceKeep", required = false) boolean forceKeep) {
    if (forceKeep) {
      forceKeepSpan();
    }
    return "Hello  I'm service " + System.getProperty("dd.service.name");
  }

  @GetMapping("/appsec/{id}")
  public String pathParam(
      @PathVariable("id") String id,
      @RequestParam(name = "url", required = false) String url,
      @RequestParam(name = "forceKeep", required = false) boolean forceKeep) {
    if (forceKeep) {
      forceKeepSpan();
    }
    if (url != null) {
      RestTemplate restTemplate = new RestTemplate();
      return restTemplate.getForObject(url, String.class);
    }
    return id;
  }

  @GetMapping("/iast")
  @SuppressFBWarnings
  public void write(
      @RequestParam(name = "injection", required = false) String injection,
      @RequestParam(name = "url", required = false) String url,
      @RequestParam(name = "forceKeep", required = false) boolean forceKeep,
      final HttpServletResponse response) {
    if (forceKeep) {
      forceKeepSpan();
    }
    if (injection != null) {
      try {
        response.getWriter().write(injection);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    if (url != null) {
      RestTemplate restTemplate = new RestTemplate();
      restTemplate.getForObject(url, String.class);
    }
  }

  /**
   * @GetMapping("/forcekeep") public String forceKeep() { return "Span " + forceKeepSpan() + " will
   * be kept alive"; } @GetMapping("/call") public String call( @RequestParam(name = "url", required
   * = false) String url, @RequestParam(name = "forceKeep", required = false) boolean forceKeep) {
   * if (forceKeep) { forceKeepSpan(); } if (url != null) { RestTemplate restTemplate = new
   * RestTemplate(); return restTemplate.getForObject(url, String.class); } return "No url
   * provided"; }
   */
  private String forceKeepSpan() {
    // TODO: Configure the keep alive in dd-trace-api
    final Span span = GlobalTracer.get().activeSpan();
    if (span != null) {
      span.setTag("manual.keep", true);
      return span.context().toSpanId();
    }
    return null;
  }
}
