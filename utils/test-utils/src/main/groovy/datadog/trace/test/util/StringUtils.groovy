package datadog.trace.test.util

class StringUtils {

  static String padHexLower(String hex, int size) {
    hex = hex.toLowerCase(Locale.ROOT)
    int left = size - hex.length()
    if (size > 0) {
      return "${'0' * left}${hex}"
    }
    return hex
  }

  static String trimHex(String hex) {
    int length = hex.length()
    int i = 0
    while (i < length  && hex.charAt(i) == '0') {
      i++
    }
    if (i == length) {
      return "0"
    }
    return hex.substring(i, length)
  }
}
