package datadog.trace.api.normalize;

import datadog.trace.api.Config;

// public because this is used in the testing module but groovy accesses it through Class.forName
// which is banned
public final class SimpleHttpPathNormalizer extends HttpPathNormalizer {
  // package private so things outside groovy for tests can't create an instance
  SimpleHttpPathNormalizer() {}

  @Override
  public String normalize(String path, boolean encoded) {
    if (null == path || path.isEmpty()) {
      return "/";
    }
    final boolean preserveSpaces =
        !encoded && Config.get().isHttpServerDecodedResourcePreserveSpaces();
    StringBuilder sb = null;
    int inEncoding = 0;
    for (int i = 0; i < path.length(); ) {
      int nextSlash = path.indexOf('/', i);
      if (nextSlash != i) {
        int endOfSegment = nextSlash == -1 ? path.length() : nextSlash;
        // detect version identifiers
        int segmentLength = (endOfSegment - i);
        if ((segmentLength <= 3 && segmentLength > 1 && (path.charAt(i) | ' ') == 'v')) {
          boolean numeric = true;
          for (int j = i + 1; j < endOfSegment; ++j) {
            numeric &= isDigit(path.charAt(j));
          }
          if (numeric) {
            if (sb != null) {
              sb.append(path, i, endOfSegment);
            }
          } else {
            sb = ensureStringBuilder(sb, path, i);
            sb.append('?');
          }
        } else {
          int snapshot = sb != null ? sb.length() : i;
          boolean numeric = false;
          for (int j = i; j < endOfSegment && !numeric; ++j) {
            final char c = path.charAt(j);
            if (encoded && c == '%') {
              inEncoding = 3;
            }
            inEncoding--;
            numeric = inEncoding < 0 && isDigit(c);
            if (!numeric) {
              if (Character.isWhitespace(c)) {
                sb = ensureStringBuilder(sb, path, j);
                if (preserveSpaces && sb.length() > 0) {
                  sb.append(c);
                }
              } else if (sb != null) {
                sb.append(c);
              }
            }
          }
          if (numeric) {
            sb = ensureStringBuilder(sb, path, snapshot);
            sb.setLength(snapshot);
            sb.append('?');
          }
        }
        i = endOfSegment + 1;
      } else {
        ++i;
      }
      if (nextSlash != -1) {
        if (sb != null) {
          sb.append('/');
        }
      }
    }
    return sb == null ? path : sb.length() == 0 ? "/" : sb.toString();
  }

  private static boolean isDigit(char c) {
    return c <= '9' && c >= '0';
  }

  private static StringBuilder ensureStringBuilder(StringBuilder sb, String path, int position) {
    if (sb == null) {
      sb = new StringBuilder();
      sb.append(path, 0, position);
    }

    return sb;
  }
}
