package datadog.trace.instrumentation.resteasy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

public final class MultipartHelper {

  private MultipartHelper() {}

  // Reflection avoids a bytecode ref to MultivaluedMap (javax→jakarta in RESTEasy 6)
  private static final Method GET_HEADERS;

  static {
    Method m = null;
    try {
      m = InputPart.class.getMethod("getHeaders");
    } catch (NoSuchMethodException ignored) {
    }
    GET_HEADERS = m;
  }

  public static List<String> collectFilenames(MultipartFormDataInput ret) {
    List<String> filenames = new ArrayList<>();
    if (GET_HEADERS == null) {
      return filenames;
    }
    for (Map.Entry<String, List<InputPart>> e : ret.getFormDataMap().entrySet()) {
      for (InputPart inputPart : e.getValue()) {
        List<String> cdHeaders;
        try {
          @SuppressWarnings("unchecked")
          Map<String, List<String>> headers =
              (Map<String, List<String>>) GET_HEADERS.invoke(inputPart);
          cdHeaders = headers != null ? headers.get("Content-Disposition") : null;
        } catch (Exception ignored) {
          continue;
        }
        if (cdHeaders == null || cdHeaders.isEmpty()) {
          continue;
        }
        String filename = filenameFromContentDisposition(cdHeaders.get(0));
        if (filename != null) {
          filenames.add(filename);
        }
      }
    }
    return filenames;
  }

  // Quote-aware: semicolons inside quoted filenames (e.g. filename="a;b.php") are not separators.
  // Outer loop: i advances to each ';' (skipping quoted strings to avoid treating their contents
  // as delimiters), then past MIME linear whitespace (SP/HT) to the start of the parameter name.
  // j is a lookahead used only to find '=' after optional whitespace without committing i until
  // the parameter is confirmed to be "filename"; this avoids confusing "filename*" (RFC 5987) or
  // other "filename"-prefixed parameter names with the plain "filename" parameter.
  public static String filenameFromContentDisposition(String cd) {
    if (cd == null) return null;
    int i = 0;
    int len = cd.length();
    while (i < len) {
      while (i < len && cd.charAt(i) != ';') {
        if (cd.charAt(i) == '"') {
          i++;
          while (i < len && cd.charAt(i) != '"') {
            if (cd.charAt(i) == '\\') i++;
            i++;
          }
        }
        i++;
      }
      if (i >= len) break;
      i++;
      while (i < len && (cd.charAt(i) == ' ' || cd.charAt(i) == '\t')) i++;
      if (cd.regionMatches(true, i, "filename", 0, 8)) {
        int j = i + 8;
        while (j < len && (cd.charAt(j) == ' ' || cd.charAt(j) == '\t')) j++;
        if (j < len && cd.charAt(j) == '=') {
          i = j + 1;
          while (i < len && (cd.charAt(i) == ' ' || cd.charAt(i) == '\t')) i++;
          if (i >= len) return null;
          if (cd.charAt(i) == '"') {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < len && cd.charAt(i) != '"') {
              if (cd.charAt(i) == '\\' && i + 1 < len) i++; // unescape
              sb.append(cd.charAt(i++));
            }
            String name = sb.toString();
            return name.isEmpty() ? null : name;
          } else {
            int start = i;
            while (i < len && cd.charAt(i) != ';') i++;
            String name = cd.substring(start, i).trim();
            return name.isEmpty() ? null : name;
          }
        }
      }
    }
    return null;
  }
}
