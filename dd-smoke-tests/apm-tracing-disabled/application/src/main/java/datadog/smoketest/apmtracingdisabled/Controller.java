package datadog.smoketest.apmtracingdisabled;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/rest-api")
public class Controller {

  private static final Logger log = LoggerFactory.getLogger(Controller.class);

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

  @GetMapping("/late-outbound")
  public String lateOutbound(@RequestParam(name = "url") String url) {
    final Span span = GlobalTracer.get().activeSpan();
    // Thread synchronization relies on waitForTraceCount rather than Thread completion, no race issue.
    Thread thread =
        new Thread(
            () -> {
              try {
                // Sleep past PendingTraceBuffer's 500ms flush delay so the root chunk exports before this late child.
                Thread.sleep(3000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              try (Scope scope = GlobalTracer.get().activateSpan(span)) {
                new RestTemplate().getForObject(url, String.class);
              } catch (Exception e) {
                log.debug("late outbound call to {} failed", url, e);
              }
            });
    thread.setDaemon(true);
    thread.start();
    return "late-outbound";
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
