import spock.lang.Specification

class HeadersUtil {
  static headersToArray(Map<String, String> headers) {
    String[] headersArr = new String[headers.size() * 2]
    headers.eachWithIndex { k, v, i ->
      headersArr[i * 2] = k
      headersArr[i * 2 + 1] = v
    }

    headersArr
  }
}

class HeadersUtilTest extends Specification {

  void 'test headers to array'() {
    setup:
    final array = expected as String[]

    when:
    final result = HeadersUtil.headersToArray(heaaders)

    then:
    result == array

    where:
    heaaders             | expected
    [:]                  | []
    ['a': 'b']           | ['a', 'b']
    ['a': 'b', 'c': 'd'] | ['a', 'b', 'c', 'd']
  }
}
