package datadog.trace.api;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MethodFilterConfigParser {
  private static final Logger log = LoggerFactory.getLogger(MethodFilterConfigParser.class);

  private static Map<String, Set<String>> logWarn(
      String message, int start, int end, String configString) {
    String part = configString.substring(start, end).trim();
    log.warn(
        "Invalid trace method config {} in part '{}'. Must match 'package.Class$Name[method1,method2];?' or 'package.Class$Name[*];?'. Config string: '{}'",
        message,
        part,
        configString);
    return Collections.emptyMap();
  }

  private static boolean hasIllegalCharacters(String string) {
    for (int i = 0; i < string.length(); i++) {
      char c = string.charAt(i);
      if (c == '*' || c == '[' || c == ']' || c == ',') {
        return true;
      }
    }
    return false;
  }

  private static boolean isIllegalClassName(String string) {
    return hasIllegalCharacters(string);
  }

  private static boolean isIllegalMethodName(String string) {
    return !string.equals("*") && hasIllegalCharacters(string);
  }

  @SuppressForbidden
  public static Map<String, Set<String>> parse(String configString) {
    Map<String, Set<String>> classMethodsToTrace;
    if (configString == null || configString.trim().isEmpty()) {
      classMethodsToTrace = Collections.emptyMap();
    } else {
      Map<String, Set<String>> toTrace = new HashMap<>();
      int start = 0;
      do {
        int next = configString.indexOf(';', start + 1);
        int end = next == -1 ? configString.length() : next;
        if (end > start + 1) {
          int methodsStart = configString.indexOf('[', start);
          if (methodsStart == -1) {
            if (!configString.substring(start).trim().isEmpty()) {
              // this had other things than trailing whitespace after the ';' which is illegal
              toTrace = logWarn("with incomplete definition", start, end, configString);
            }
            break;
          } else if (methodsStart >= end) {
            // this part didn't contain a '[' or ended in a '[' which is illegal
            toTrace = logWarn("with incomplete method definition", start, end, configString);
            break;
          }
          int methodsEnd = configString.indexOf(']', methodsStart);
          if (methodsEnd == -1 || methodsEnd > end) {
            // this part didn't contain a ']' which is illegal
            toTrace = logWarn("does not contain a ']'", start, end, configString);
            break;
          } else if (methodsEnd < end
              && !configString.substring(methodsEnd + 1, end).trim().isEmpty()) {
            // this had other things than trailing whitespace after the ']'
            toTrace = logWarn("with extra characters after ']'", start, end, configString);
            break;
          }
          String className = configString.substring(start, methodsStart).trim();
          if (className.isEmpty() || isIllegalClassName(className)) {
            toTrace = logWarn("with illegal class name", start, end, configString);
            break;
          }
          Set<String> methodNames = toTrace.get(className);
          if (null == methodNames) {
            methodNames = new HashSet<>();
            toTrace.put(className, methodNames);
          }
          int methods = 0;
          int emptyMethods = 0;
          boolean hasStar = false;
          for (int methodStart = methodsStart + 1; methodStart < methodsEnd; ) {
            int nextComma = configString.indexOf(',', methodStart);
            int methodEnd = nextComma == -1 || nextComma >= methodsEnd ? methodsEnd : nextComma;
            String method = configString.substring(methodStart, methodEnd).trim();
            if (isIllegalMethodName(method)) {
              toTrace = logWarn("with illegal method name", start, end, configString);
              methods++; // don't log empty method warning at end
              next = -1;
              break;
            } else if (method.isEmpty()) {
              emptyMethods++;
              if (emptyMethods > 1) {
                // we can't have multiple empty methods
                toTrace = logWarn("with multiple emtpy method names", start, end, configString);
                methods++; // don't log empty method warning at end
                next = -1;
                break;
              }
            } else {
              methods++;
              if (emptyMethods > 0) {
                // the empty method name was not the last one, which makes it illegal
                toTrace =
                    logWarn("with method name and emtpy method name", start, end, configString);
                next = -1;
                break;
              }
              hasStar |= method.indexOf('*') != -1;
              if (hasStar && methods > 1) {
                // having both a method and a '*' is illegal
                toTrace = logWarn("with both method name and '*'", start, end, configString);
                next = -1;
                break;
              }
              methodNames.add(method);
            }
            methodStart = methodEnd + 1;
          }
          if (methods == 0) {
            // empty method description is illegal
            toTrace = logWarn("with empty method definition", start, end, configString);
            break;
          }
        }
        start = next + 1;
      } while (start != 0);
      classMethodsToTrace = Collections.unmodifiableMap(toTrace);
    }
    return classMethodsToTrace;
  }
}
