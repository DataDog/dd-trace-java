package com.example.regexbench;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AntiPatternController {

  static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

  static final String PAYLOAD =
      "id=1042, user=jane.doe@example.com, name=Jane Doe, city=San Francisco, zip=94107,"
          + " phone=415-555-0132, tags=alpha; beta; gamma, ref=ORD-2024-000981,"
          + " contact=ops@acme.io, note=Thanks for the quick turnaround!";

  private String payload = PAYLOAD;

  private final String[] fieldValues = fieldValues(PAYLOAD);

  @GetMapping("/split")
  public String split() {
    String[] parts = payload.split(",\\s*");
    return parts.length + ":" + parts[parts.length - 1].length();
  }

  @GetMapping("/sanitize")
  public String sanitize() {
    return Integer.toString(payload.replaceAll("[^A-Za-z0-9]", "").length());
  }

  @GetMapping("/email")
  public String email() {
    int valid = 0;
    for (String value : fieldValues) {
      if (value.matches(EMAIL_REGEX)) {
        valid++;
      }
    }
    return Integer.toString(valid);
  }

  @GetMapping("/mask")
  public String mask() {
    return Integer.toString(payload.replaceFirst("(\\w+)@(\\w+)\\.com", "$1@***.com").length());
  }

  @GetMapping("/compile")
  public String compile() {
    Pattern p = Pattern.compile("(\\w+)=(\\d+)");
    Matcher m = p.matcher(payload);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return Integer.toString(n);
  }

  @GetMapping("/dynamic")
  public String dynamic(@RequestParam(name = "sep", defaultValue = ",") String sep) {
    String[] parts = payload.split(sep + "\\s*");
    return parts.length + ":" + parts[parts.length - 1].length();
  }

  @GetMapping("/noregex")
  public String noregex() {
    String s = payload;
    int count = 0;
    int last = 0;
    int start = 0;
    while (start <= s.length()) {
      int comma = s.indexOf(',', start);
      int end = comma < 0 ? s.length() : comma;
      last = s.substring(start, end).trim().length();
      count++;
      if (comma < 0) {
        break;
      }
      start = comma + 1;
    }
    return count + ":" + last;
  }

  private static String[] fieldValues(String payload) {
    List<String> values = new ArrayList<>();
    int start = 0;
    while (start < payload.length()) {
      int comma = payload.indexOf(',', start);
      int end = comma < 0 ? payload.length() : comma;
      String field = payload.substring(start, end).trim();
      int eq = field.indexOf('=');
      values.add(eq < 0 ? field : field.substring(eq + 1));
      start = end + 1;
    }
    return values.toArray(new String[0]);
  }
}
