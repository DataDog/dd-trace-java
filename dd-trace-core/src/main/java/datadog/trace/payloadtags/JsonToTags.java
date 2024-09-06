package datadog.trace.payloadtags;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonToTags {

  static final Logger log = LoggerFactory.getLogger(JsonToTags.class);

  private static final String DD_PAYLOAD_TAGS_INCOMPLETE = "_dd.payload_tags_incomplete";

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
      if (rules == null || rules.isEmpty()) {
        return Collections.emptyList();
      }
      List<JsonPath> result = new ArrayList<>(rules.size());
      for (String rule : rules) {
        try {
          JsonPath jp = JsonPath.compile(rule);
          result.add(jp);
        } catch (Exception ex) {
          log.debug("Skipping failed to parse JSON path rule: '{}'", rule);
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

  private final ParseContext parseContext;

  static {
    // ensure that no default minidev json-smart provider is used
    // this is necessary for graal native to prevent NoClassDefFoundError
    Configuration.setDefaults(
        new Configuration.Defaults() {
          private final JsonProvider jsonProvider = new MoshiJsonProvider();
          private final MappingProvider mappingProvider = new MoshiMappingProvider();

          @Override
          public JsonProvider jsonProvider() {
            return jsonProvider;
          }

          @Override
          public MappingProvider mappingProvider() {
            return mappingProvider;
          }

          @Override
          public Set<Option> options() {
            return EnumSet.noneOf(Option.class);
          }
        });
  }

  private JsonToTags(
      List<JsonPath> expansionRules,
      List<JsonPath> redactionRules,
      int limitTags,
      int limitDeepness) {
    this.expansionRules = expansionRules;
    this.redactionRules = redactionRules;
    this.limitTags = limitTags;
    this.limitDeepness = limitDeepness;

    Configuration configuration =
        Configuration.builder()
            .jsonProvider(new MoshiJsonProvider())
            .mappingProvider(new MoshiMappingProvider())
            .build();

    parseContext = JsonPath.using(configuration);
  }

  public Map<String, Object> process(InputStream is, String tagPrefix) {
    DocumentContext dc;

    try {
      dc = parseContext.parse(is);
    } catch (Exception ex) {
      log.debug("Failed to parse JSON body for tag extraction", ex);
      return Collections.emptyMap();
    }

    if (!(dc.json() instanceof Map)) {
      log.debug("Failed to parse JSON body for tag extraction. Expected JSON object at the root.");
      return Collections.emptyMap();
    }

    for (JsonPath jp : expansionRules) {
      try {
        dc.map(jp, (obj, conf) -> expandInnerJson(jp, obj));
      } catch (PathNotFoundException ex) {
        // ignore
      }
    }

    for (JsonPath jp : redactionRules) {
      try {
        dc.set(jp, REDACTED);
      } catch (PathNotFoundException ex) {
        // ignore
      }
    }

    LinkedHashMap<String, Object> tags = new LinkedHashMap<>();
    boolean visitedAll =
        traverse(
            dc.json(),
            new StringBuilder(),
            (path, value) -> {
              tags.put(tagPrefix + path, value);
              return tags.size() < limitTags;
            },
            0);

    if (!visitedAll) {
      tags.put(DD_PAYLOAD_TAGS_INCOMPLETE, true);
    }

    return tags;
  }

  private Object expandInnerJson(JsonPath jp, Object obj) {
    if (obj instanceof String) {
      String str = (String) obj;
      if (!str.startsWith("{") && !str.startsWith("[")) {
        log.debug(
            "Couldn't expand inner JSON {} for path: {} because it neither start with { or [",
            str,
            jp.getPath());
        return str;
      }
      try {
        return parseContext.parse((String) obj).json();
      } catch (InvalidJsonException ex) {
        log.debug("Failed to parse inner JSON for path: {}", jp.getPath(), ex);
      }
    }
    return obj;
  }

  private interface JsonVisitor {
    /* return true to continue or false to stop visiting */
    boolean visit(String path, Object value);
  }

  private boolean traverse(Object json, StringBuilder pathBuf, JsonVisitor visitor, int depth) {
    if (depth > limitDeepness) {
      return true;
    }
    if (json instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) json;
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        int i = pathBuf.length();
        pathBuf.append('.');
        pathBuf.append(entry.getKey().replace(".", "\\."));
        boolean visitedAll = traverse(entry.getValue(), pathBuf, visitor, depth + 1);
        pathBuf.delete(i, pathBuf.length());
        if (!visitedAll) {
          return false;
        }
      }
    } else if (json instanceof Iterable) {
      Iterable<Object> iterable = (Iterable<Object>) json;
      int index = 0;
      for (Object item : iterable) {
        int i = pathBuf.length();
        pathBuf.append('.');
        pathBuf.append(index);
        boolean visitedAll = traverse(item, pathBuf, visitor, depth + 1);
        pathBuf.delete(i, pathBuf.length());
        if (!visitedAll) {
          return false;
        }
        index += 1;
      }
    } else {
      return visitor.visit(pathBuf.toString(), json);
    }
    return true;
  }
}
