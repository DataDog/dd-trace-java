package datadog.trace.core.tagprocessor;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.util.Strings;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryObfuscator implements TagsPostProcessor {

  private static final Logger log = LoggerFactory.getLogger(QueryObfuscator.class);

  private static final String DEFAULT_OBFUSCATION_PATTERN =
      "((?i)(?:p(?:ass)?w(?:or)?d|pass(?:_?phrase)?|secret|(?:api_?|private_?|public_?|access_?|secret_?)key(?:_?id)?|token|consumer_?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:(?:\\s|%20)*(?:=|%3D)[^&]+|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))|bearer(?:\\s|%20)+[a-z0-9\\._\\-]|token(?::|%3A)[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+\\/=-]|%3D|%2F|%2B)+)?|[\\-]{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY[\\-]{5}[^\\-]+[\\-]{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY|ssh-rsa(?:\\s|%20)*(?:[a-z0-9\\/\\.+]|%2F|%5C|%2B){100,})";

  private final Pattern pattern;

  /**
   * If regex is null - then used default regex pattern If regex is empty string - then disable
   * regex
   */
  public QueryObfuscator(String regex) {
    // empty string -> disabled query obfuscation
    if ("".equals(regex)) {
      this.pattern = null;
      return;
    }

    // null -> use default regex
    if (regex == null) {
      regex = DEFAULT_OBFUSCATION_PATTERN;
    }

    Pattern pattern = null;
    try {
      pattern = Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      log.error("Could not compile given query obfuscation regex: {}", regex, e);
    }
    this.pattern = pattern;
  }

  private String obfuscate(String query) {
    if (pattern != null) {
      Matcher matcher = pattern.matcher(query);
      while (matcher.find()) {
        query = Strings.replace(query, matcher.group(), "<redacted>");
      }
    }
    return query;
  }

  @Override
  public Map<String, Object> processTags(Map<String, Object> unsafeTags) {
    Object query = unsafeTags.get(DDTags.HTTP_QUERY);
    if (query instanceof CharSequence) {
      query = obfuscate(query.toString());

      unsafeTags.put(DDTags.HTTP_QUERY, query);

      Object url = unsafeTags.get(Tags.HTTP_URL);
      if (url instanceof CharSequence) {
        unsafeTags.put(Tags.HTTP_URL, url + "?" + query);
      }
    }

    return unsafeTags;
  }
}
