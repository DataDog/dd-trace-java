package datadog.crashtracking.parsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility for normalizing and filtering JVM runtime arguments captured from crash artifacts.
 *
 * <p>The helper supports two input shapes:
 *
 * <ul>
 *   <li>a raw JVM-args record, such as HotSpot {@code jvm_args: ...}
 *   <li>pre-split J9 user arguments, such as individual {@code 2CIUSERARG} records
 * </ul>
 *
 * <p>Only a curated subset of arguments is retained for telemetry: {@code -Ddd.*}, {@code -Djdk.*},
 * {@code -Djava.*}, {@code -Dsun.*}, {@code -javaagent:}, {@code -agentlib:}, {@code -X*}, and
 * module/native-access options.
 */
final class RuntimeArgs {
  // Aligned with JDK JEP-8372760 (JFR In-Process Data Redaction) default filter list.
  private static final String[] SECRET_PROPERTY_KEYWORDS = {
    "auth", "password", "passwd", "pwd", "passphrase", "secret", "token", "key", "credential"
  };
  private static final String[] MODULE_OPTIONS = {
    "--add-modules",
    "--add-exports",
    "--add-opens",
    "--add-reads",
    "--patch-module",
    "--limit-modules",
    "--module-path",
    "--upgrade-module-path",
    "--enable-native-access",
    "--illegal-native-access",
    "--sun-misc-unsafe-memory-access"
  };

  private final List<String> args = new ArrayList<>();

  /** Returns a filtered args list from the raw JVM-arguments. */
  static List<String> parseVmArgs(String raw) {
    return filterArgs(joinArgumentTokens(splitArgs(raw)));
  }

  void addArg(String arg) {
    if (arg != null && !arg.isEmpty()) {
      args.add(arg);
    }
  }

  List<String> build() {
    return filterArgs(args);
  }

  private static List<String> filterArgs(List<String> args) {
    if (args.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filtered = new ArrayList<>();
    for (String arg : args) {
      if (arg == null || arg.isEmpty()) {
        continue;
      }
      if (isAllowedSystemProperty(arg)) {
        filtered.add(arg);
      } else if (arg.startsWith("-javaagent:") || arg.startsWith("-agentlib:")) {
        // Redact options after '=' — only the jar path / library name is sent
        int eq = arg.indexOf('=', arg.indexOf(':') + 1);
        filtered.add(eq >= 0 ? arg.substring(0, eq) + "=REDACTED" : arg);
      } else if (arg.startsWith("-X") || isModuleOrNativeAccessOption(arg)) {
        filtered.add(arg);
      }
    }
    return filtered;
  }

  private static boolean isModuleOrNativeAccessOption(String arg) {
    for (String option : MODULE_OPTIONS) {
      if (arg.equals(option) || arg.startsWith(option + "=") || arg.startsWith(option + " ")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAllowedSystemProperty(String arg) {
    if (hasSecretLikePropertyName(arg)) {
      return false;
    }
    if (arg.startsWith("-Ddd.") || arg.startsWith("-Djdk.") || arg.startsWith("-Dosgi.")) {
      return true;
    }
    // J9 lists them as vm args.
    if (arg.startsWith("-Djava.class.path=") || arg.startsWith("-Dsun.java.command=")) {
      return false;
    }
    return arg.startsWith("-Djava.") || arg.startsWith("-Dsun.");
  }

  private static boolean hasSecretLikePropertyName(String arg) {
    if (!arg.startsWith("-D")) {
      return false;
    }
    int separator = arg.indexOf('=');
    String propertyName = separator >= 0 ? arg.substring(2, separator) : arg.substring(2);
    String normalizedPropertyName = propertyName.toLowerCase();
    for (String keyword : SECRET_PROPERTY_KEYWORDS) {
      if (normalizedPropertyName.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> splitArgs(String raw) {
    if (raw == null || raw.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder(raw.length());
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    boolean escaped = false;

    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      // Keep the escaped character verbatim and clear the escape state.
      if (escaped) {
        current.append(c);
        escaped = false;
        continue;
      }
      // Backslashes escape the next character unless we are inside single quotes.
      if (c == '\\' && !inSingleQuote) {
        escaped = true;
        continue;
      }
      // Single quotes only toggle quoting when not already inside double quotes.
      if (c == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
        continue;
      }
      // Double quotes only toggle quoting when not already inside single quotes.
      if (c == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
        continue;
      }
      // Outside of quotes, whitespace terminates the current token.
      if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(c);
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  /**
   * Joins shell-like tokens into argument-shaped units.
   *
   * <p>This pass reconstructs options that span multiple tokens, such as module options whose value
   * is separated by whitespace and {@code -XX:OnError=} style options that carry shell fragments
   * over multiple tokens.
   */
  private static List<String> joinArgumentTokens(List<String> tokens) {
    if (tokens.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> joinedArgs = new ArrayList<>();
    boolean canContinuePreviousArg = false;
    StringBuilder argBuilder = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (token.isEmpty()) {
        continue;
      }
      if (!token.startsWith("-")) {
        if (canContinuePreviousArg
            && !joinedArgs.isEmpty()
            && isContinuationToken(token)
            && acceptsContinuation(joinedArgs.get(joinedArgs.size() - 1))) {
          int last = joinedArgs.size() - 1;
          argBuilder.setLength(0);
          argBuilder.append(joinedArgs.get(last)).append(' ').append(token);
          joinedArgs.set(last, argBuilder.toString());
          continue;
        }
        canContinuePreviousArg = false;
        continue;
      }

      argBuilder.setLength(0);
      argBuilder.append(token);
      if (requiresSeparateValue(token) && i + 1 < tokens.size()) {
        argBuilder.append(' ').append(tokens.get(++i));
      }
      while (i + 1 < tokens.size()
          && isContinuationToken(tokens.get(i + 1))
          && acceptsContinuation(argBuilder.toString())) {
        argBuilder.append(' ').append(tokens.get(++i));
      }
      String arg = argBuilder.toString();
      joinedArgs.add(arg);
      canContinuePreviousArg = acceptsContinuation(arg);
    }
    return joinedArgs;
  }

  private static boolean requiresSeparateValue(String token) {
    for (String option : MODULE_OPTIONS) {
      if (token.equals(option)) {
        return true;
      }
    }
    return false;
  }

  private static boolean acceptsContinuation(String arg) {
    return arg.startsWith("-XX:OnError=")
        || arg.startsWith("-XX:OnOutOfMemoryError=")
        || arg.startsWith("-Xdump:");
  }

  private static boolean isContinuationToken(String token) {
    return !token.isEmpty() && !token.startsWith("-");
  }
}
