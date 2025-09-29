class HeadersUtil {
  static headersToArray(Map<String, String> headers) {
    String[] headersArr = new String[headers.size() * 2]
    headers.eachWithIndex { k, v, i ->
      headersArr[i] = k
      headersArr[i + 1] = v
    }

    headersArr
  }
}
