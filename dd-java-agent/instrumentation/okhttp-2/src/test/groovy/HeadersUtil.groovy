class HeadersUtil {
  static headersToArray(List<List<String>> headers) {
    String[] headersArr = new String[headers.size() * 2]
    headers.eachWithIndex { header, i ->
      headersArr[i] = header[0]
      headersArr[i + 1] = header[1]
    }

    headersArr
  }
}
