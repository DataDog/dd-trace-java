package datadog.trace.util;

import java.nio.charset.Charset;

public final class Strings {

  public static String join(
      final CharSequence joiner, final Iterable<? extends CharSequence> strings) {
    final StringBuilder sb = new StringBuilder();
    for (final CharSequence string : strings) {
      sb.append(string).append(joiner);
    }
    // truncate to remove the last joiner
    if (sb.length() > 0) {
      sb.setLength(sb.length() - joiner.length());
    }
    return sb.toString();
  }

  public static String join(final CharSequence joiner, final CharSequence... strings) {
    if (strings.length > 0) {
      final StringBuilder sb = new StringBuilder();
      sb.append(strings[0]);
      for (int i = 1; i < strings.length; ++i) {
        sb.append(joiner).append(strings[i]);
      }
      return sb.toString();
    }
    return "";
  }

  public static byte[] getBytes(final String string, final Charset charset) {
    if (string == null) {
      return null;
    }
    return string.getBytes(charset);
  }
}
