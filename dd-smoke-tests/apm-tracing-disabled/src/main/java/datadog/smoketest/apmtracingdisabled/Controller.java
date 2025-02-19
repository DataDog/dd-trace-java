package datadog.smoketest.apmtracingdisabled;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/rest-api")
public class Controller {

  @GetMapping("/greetings")
  public String greetings(
      @RequestParam(name = "url", required = false) String url,
      @RequestParam(name = "forceKeep", required = false) boolean forceKeep) {
    if (forceKeep) {
      forceKeepSpan();
    }
    if (url != null) {
      RestTemplate restTemplate = new RestTemplate();
      return restTemplate.getForObject(url, String.class);
    }
    return "Hello  I'm service " + System.getProperty("dd.service.name");
  }

  @GetMapping(value = "/returnheaders", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, String>> returnheaders(
      @RequestHeader Map<String, String> headers) {
    return ResponseEntity.ok(headers);
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

  private String forceKeepSpan() {
    final Span span = GlobalTracer.get().activeSpan();
    if (span != null) {
      span.setTag("manual.keep", true);
      return span.context().toSpanId();
    }
    return null;
  }
}
