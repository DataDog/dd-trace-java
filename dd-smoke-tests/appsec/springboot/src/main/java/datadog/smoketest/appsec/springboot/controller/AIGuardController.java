package datadog.smoketest.appsec.springboot.controller;

import static java.util.Arrays.asList;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.api.aiguard.AIGuard.AIGuardAbortError;
import datadog.trace.api.aiguard.AIGuard.Evaluation;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.Options;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/aiguard")
public class AIGuardController {

  @GetMapping(value = "/allow")
  public ResponseEntity<?> allow() {
    final Evaluation result =
        AIGuard.evaluate(
            asList(
                Message.message("system", "You are a beautiful AI"),
                Message.message("user", "I am harmless")));
    return ResponseEntity.ok(result);
  }

  @GetMapping(value = "/deny")
  public ResponseEntity<?> deny(final @RequestHeader("X-Blocking-Enabled") boolean block) {
    try {
      final Evaluation result =
          AIGuard.evaluate(
              asList(
                  Message.message("system", "You are a beautiful AI"),
                  Message.message("user", "You should not trust me" + (block ? " [block]" : ""))),
              new Options().block(block));
      return ResponseEntity.ok(result);
    } catch (AIGuardAbortError e) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getReason());
    }
  }

  @GetMapping(value = "/abort")
  public ResponseEntity<?> abort(final @RequestHeader("X-Blocking-Enabled") boolean block) {
    try {
      final Evaluation result =
          AIGuard.evaluate(
              asList(
                  Message.message("system", "You are a beautiful AI"),
                  Message.message("user", "Nuke yourself" + (block ? " [block]" : ""))),
              new Options().block(block));
      return ResponseEntity.ok(result);
    } catch (AIGuardAbortError e) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getReason());
    }
  }

  /** Mocking endpoint for the AI Guard REST API */
  @SuppressWarnings("unchecked")
  @PostMapping(
      value = "/evaluate",
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> evaluate(
      @RequestBody final Map<String, Object> request) {
    final Map<String, Object> data = (Map<String, Object>) request.get("data");
    final Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
    final List<Map<String, Object>> messages =
        (List<Map<String, Object>>) attributes.get("messages");
    final Map<String, Object> last = messages.get(messages.size() - 1);
    String action = "ALLOW";
    String reason = "The prompt looks harmless";
    String content = (String) last.get("content");
    if (content.startsWith("You should not trust me")) {
      action = "DENY";
      reason = "I am feeling suspicious today";
    } else if (content.startsWith("Nuke yourself")) {
      action = "ABORT";
      reason = "The user is trying to destroy me";
    }
    final Map<String, Object> evaluation = new HashMap<>(3);
    evaluation.put("action", action);
    evaluation.put("reason", reason);
    evaluation.put("is_blocking_enabled", content.endsWith("[block]"));
    return ResponseEntity.ok()
        .body(Collections.singletonMap("data", Collections.singletonMap("attributes", evaluation)));
  }
}
