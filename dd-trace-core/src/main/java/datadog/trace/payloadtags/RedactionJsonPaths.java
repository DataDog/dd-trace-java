package datadog.trace.payloadtags;

import datadog.trace.payloadtags.json.JsonPath;
import datadog.trace.payloadtags.json.JsonPathParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedactionJsonPaths {

  static final Logger log = LoggerFactory.getLogger(RedactionJsonPaths.class);

  public static final class Builder {
    private final List<JsonPath> redactionRules = new ArrayList<>();

    public Builder addRedactionJsonPaths(List<String> jsonPaths) {
      this.redactionRules.addAll(parseJsonPaths(jsonPaths));
      return this;
    }

    private static List<JsonPath> parseJsonPaths(List<String> rules) {
      if (rules.isEmpty() || rules.size() == 1 && rules.get(0).equalsIgnoreCase("all")) {
        return Collections.emptyList();
      }
      List<JsonPath> result = new ArrayList<>(rules.size());
      for (String rule : rules) {
        try {
          JsonPath jp = JsonPathParser.parse(rule);
          result.add(jp);
        } catch (Exception ex) {
          log.debug("Skipping failed to parse redaction jsonpath: '{}'. {}", rule, ex.getMessage());
        }
      }
      return result;
    }

    public RedactionJsonPaths build() {
      return new RedactionJsonPaths(redactionRules);
    }
  }

  private final List<JsonPath> paths;

  private RedactionJsonPaths(List<JsonPath> paths) {
    this.paths = paths;
  }

  public JsonPath findMatching(PathCursor pathCursor) {
    for (JsonPath jp : paths) {
      if (jp.matches(pathCursor)) {
        return jp;
      }
    }
    return null;
  }
}
