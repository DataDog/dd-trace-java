package datadog.trace.bootstrap.instrumentation.decorator.http;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.util.Strings;

public class QueryObfuscator {

  private static final String DEFAULT_OBFUSCATION_PATTERN =
      "(?i)(?:p(?:ass)?w(?:or)?d|pass(?:_?phrase)?|secret|(?:api_?|private_?|public_?|access_?|secret_?)key(?:_?id)?|token|consumer_?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:\\s*=[^&]+|\"\\s*:\\s*\"[^\"]+\")|bearer\\s+[a-z0-9\\._\\-]|token:[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L][\\w=-]+\\.ey[I-L][\\w=-]+(?:\\.[\\w.+\\/=-]+)?|[\\-]{5}BEGIN[a-z\\s]+PRIVATE\\sKEY[\\-]{5}[^\\-]+[\\-]{5}END[a-z\\s]+PRIVATE\\sKEY|ssh-rsa\\s*[a-z0-9\\/\\.+]{100,}";

  public static String obfuscate(String query, String regex) {
    // Empty regex - means obfuscation deactivated
    if (query == null || "".equals(regex)) {
      return query;
    }

    // if encoded
    query = URIUtils.decode(query);
    if (query.isEmpty()) {
      return query;
    }

    // Use default if regex is undefined
    if (regex == null) {
      regex = DEFAULT_OBFUSCATION_PATTERN;
    }

    Matcher matcher = Pattern.compile(regex).matcher(query);
    while (matcher.find()) {
      query = Strings.replace(query, matcher.group(), "<redacted>");
    }
    return query;
  }
}
