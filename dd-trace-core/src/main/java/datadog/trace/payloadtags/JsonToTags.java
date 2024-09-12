package datadog.trace.payloadtags;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okio.BufferedSource;
import okio.Okio;
import org.jsfr.json.compiler.JsonPathCompiler;
import org.jsfr.json.path.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonToTags {

  static final Logger log = LoggerFactory.getLogger(JsonToTags.class);

  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

  private final Moshi moshi = new Moshi.Builder().build();
  private final JsonAdapter<Object> jsonAdapter = moshi.adapter(Object.class).lenient();

  public static final class Builder {
    private List<JsonPath> expansionRules = Collections.emptyList();
    private List<JsonPath> redactionRules = Collections.emptyList();
    private int limitTags = 784;
    private int limitDeepness = 10;
    // for test purposes only
    Builder parseExpansionRules(List<String> rules) {
      this.expansionRules = parseRules(rules);
      return this;
    }

    public Builder parseRedactionRules(List<String> rules) {
      this.redactionRules = parseRules(rules);
      return this;
    }

    private static List<JsonPath> parseRules(List<String> rules) {
      if (rules.isEmpty() || rules.size() == 1 && rules.get(0).equalsIgnoreCase("all")) {
        return Collections.emptyList();
      }
      List<JsonPath> result = new ArrayList<>(rules.size());
      for (String rule : rules) {
        try {
          JsonPath jp = JsonPathCompiler.compile(rule);
          result.add(jp);
        } catch (Exception ex) {
          log.debug("Skipping failed to parse JSON path rule: '{}'", rule, ex);
        }
      }
      return result;
    }

    public Builder limitTags(int limitTags) {
      this.limitTags = limitTags;
      return this;
    }

    public Builder limitDeepness(int limitDeepness) {
      this.limitDeepness = limitDeepness;
      return this;
    }

    public JsonToTags build() {
      return new JsonToTags(expansionRules, redactionRules, limitTags, limitDeepness);
    }
  }

  private static final String REDACTED = "redacted";

  private final List<JsonPath> expansionRules;
  private final List<JsonPath> redactionRules;
  private final int limitTags;
  private final int limitDeepness;

  private JsonToTags(
      List<JsonPath> expansionRules,
      List<JsonPath> redactionRules,
      int limitTags,
      int limitDeepness) {
    this.expansionRules = expansionRules;
    this.redactionRules = redactionRules;
    this.limitTags = limitTags;
    this.limitDeepness = limitDeepness;
  }

  public Map<String, Object> process(InputStream is, String tagPrefix) {
    Object json;

    try {
      json = parse(is);
    } catch (IOException ex) {
      log.debug("Failed to parse JSON body for tag extraction: {}", ex.getMessage());
      return Collections.emptyMap();
    }

    if (!(json instanceof Map)) {
      log.debug("Failed to parse JSON body for tag extraction. Expected JSON object at the root.");
      return Collections.emptyMap();
    }

    LinkedHashMap<String, Object> tags = new LinkedHashMap<>();
    boolean visitedAll =
        traverse(json, new StringBuilder(tagPrefix), JsonPosition.start(), 0, tags);

    if (!visitedAll) {
      tags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
    }

    return tags;
  }

  private Object parse(InputStream is) throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(is))) {
      return jsonAdapter.fromJson(source);
    }
  }

  public Object parse(String s) throws IOException {
    return jsonAdapter.fromJson(s);
  }

  private Object expandInnerJson(CharSequence path, String str) {
    if (!str.startsWith("{") && !str.startsWith("[")) {
      log.debug(
          "Couldn't expand inner JSON {} for path: {} because it neither start with { or [",
          str,
          path);
    } else {
      try {
        return parse(str);
      } catch (IOException ex) {
        log.debug("Failed to parse inner JSON for path: {}", path, ex);
      }
    }
    return null;
  }

  private boolean traverse(
      Object jsonValue,
      StringBuilder pathBuf,
      JsonPosition jp,
      int depth,
      Map<String, Object> tagsAcc) {
    if (depth > limitDeepness) {
      return true;
    }
    if (jsonValue instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) jsonValue;
      jp.stepIntoObject();
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        int i = pathBuf.length();
        pathBuf.append('.');
        pathBuf.append(entry.getKey().replace(".", "\\."));
        jp.updateObjectEntry(entry.getKey());
        boolean visitedAll = traverse(entry.getValue(), pathBuf, jp, depth + 1, tagsAcc);
        pathBuf.delete(i, pathBuf.length());
        if (!visitedAll) {
          return false;
        }
      }
      jp.stepOutObject();
    } else if (jsonValue instanceof Iterable) {
      Iterable<Object> iterable = (Iterable<Object>) jsonValue;
      int index = 0;
      jp.stepIntoArray();
      for (Object item : iterable) {
        int i = pathBuf.length();
        pathBuf.append('.');
        pathBuf.append(index);
        jp.accumulateArrayIndex();
        boolean visitedAll = traverse(item, pathBuf, jp, depth + 1, tagsAcc);
        pathBuf.delete(i, pathBuf.length());
        if (!visitedAll) {
          return false;
        }
        index += 1;
      }
      jp.stepOutArray();
    } else {
      if (jsonValue instanceof String) {
        for (JsonPath er : expansionRules) {
          if (er.matchWithDeepScan(jp)) {
            Object innerJson = expandInnerJson(pathBuf, (String) jsonValue);
            if (innerJson == null) {
              // matched but failed to expand
              // skip expansion
              break;
            }
            return traverse(innerJson, pathBuf, jp, 0, tagsAcc);
          }
        }
      }

      for (JsonPath rr : redactionRules) {
        if (rr.matchWithDeepScan(jp)) {
          tagsAcc.put(pathBuf.toString(), REDACTED);
          return tagsAcc.size() < limitTags;
        }
      }

      tagsAcc.put(pathBuf.toString(), jsonValue);
      return tagsAcc.size() < limitTags;
    }
    return true;
  }
}
