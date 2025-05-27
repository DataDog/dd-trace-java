package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.test.util.DDSpecification

import static datadog.environment.JavaVirtualMachine.isJavaVersion

class URIUtilsTest extends DDSpecification {
  static boolean java7 = isJavaVersion(7)

  def "should build urls \"#input\""() {
    setup:
    def uri = new URI(input)
    def url = URIUtils.buildURL(uri.scheme, uri.host, uri.port, uri.path)
    def lazyUrl = URIUtils.lazyValidURL(uri.scheme, uri.host, uri.port, uri.path)

    expect:
    url == expected
    lazyUrl.path() == uri.path
    lazyUrl.get() == expected
    lazyUrl.toString() == expected

    where:
    input                         | expected
    "https://host:0"              | "https://host/"
    "http://host/?query"          | "http://host/"
    "https://host/path"           | "https://host/path"
    "http://host:47/path?query"   | "http://host:47/path"
    "http://host:80/path?query"   | "http://host/path"
    "http://host:443/path?query"  | "http://host:443/path"
    "https://host:443/path?query" | "https://host/path"
    "https://host:80/path?query"  | "https://host:80/path"
  }

  def "should build urls from corner cases \"#scheme\" \"#host\" #port \"#path\""() {
    setup:
    def url = URIUtils.buildURL(scheme, host, port, path)
    def lazyUrl = URIUtils.lazyValidURL(scheme, host, port, path)

    expect:
    url == expected
    lazyUrl.path() == (path != null ? path : '')
    lazyUrl.toString() == expected
    lazyUrl.get() == expected

    where:
    scheme | host | port | path          | expected
    null   | null | -1   | null          | "/"
    ""     | ""   | -1   | ""            | ":///"
    ""     | ""   | -1   | "relative"    | ":///relative"
    ""     | ""   | -1   | "/absolute"   | ":///absolute"
    null   | null | -1   | "relative"    | "relative"
    null   | null | -1   | "/absolute"   | "/absolute"
  }

  def "should decode URL-encoded ignoring + \"#encoded\" -> \"#expected\""() {
    setup:
    def decoded = URIUtils.decode(encoded)

    expect:
    decoded == expected

    where:
    encoded                             | expected
    null                                | null
    ""                                  | ""
    "plain"                             | "plain"
    "%C3%BEungur%20hn%C3%ADfur"         | "þungur hnífur"
    "tr%C3%A5kig%20str%C3%A4ng+med+%2B" | "tråkig sträng+med++"
    "boring+string+with+only+plus"      | "boring+string+with+only+plus"
    "%"                                 | "�"                         // invalid % sequence with too few characters
    "%1"                                | "�"                         // invalid % sequence with too few characters
    "%W1"                               | "�"                         // invalid % sequence with illegal 1st character
    "%1X"                               | "�"                         // invalid % sequence with illegal 2nd character
    "wh%Y1t"                            | "wh�t"                      // invalid % sequence with illegal 1st character
    "wh%1Zt"                            | "wh�t"                      // invalid % sequence with illegal 2nd character
    "wh%"                               | "wh�"                       // invalid % sequence with too few characters
    "wh%1"                              | "wh�"                       // invalid % sequence with too few characters
    "%C3%28"                            | "�("                        // invalid 2 byte sequence
    "%A0%A1"                            | "��"                        // invalid sequence identifier
    "%E2%28%A1"                         | "�(�"                       // invalid 3 byte sequence in 2nd byte
    "%E2%82%28"                         | "�("                        // invalid 3 byte sequence in 3rd byte
    "%F0%28%8C%BC"                      | "�(��"                      // invalid 4 byte sequence in 2nd byte
    "%F0%90%28%BC"                      | "�(�"                       // invalid 4 byte sequence in 3rd byte
    "%F0%28%8C%28"                      | "�(�("                      // invalid 4 byte sequence in 4th byte
    "%F8%A1%A1%A1%A1"                   | "${java7 ? "�" : "�����"}"  // valid 5 byte sequence but not unicode
    "%FC%A1%A1%A1%A1%A1"                | "${java7 ? "�" : "������"}" // valid 6 byte sequence but not unicode
  }

  def "should decode URL-encoded with + \"#encoded\" -> \"#expected\""() {
    setup:
    def decoded = URIUtils.decode(encoded, true)

    expect:
    decoded == expected

    where:
    encoded                                          | expected
    null                                             | null
    "plain"                                          | "plain"
    "%C3%BEungur%20hn%C3%ADfur"                      | "þungur hnífur"
    "v%C3%A4ldigt+tr%C3%A5kig%20str%C3%A4ng+med+%2B" | "väldigt tråkig sträng med +"
    "very+boring+string+with+only+plus"              | "very boring string with only plus"
  }

  def "test LazyUrl for code coverage"() {
    when:
    def raw = 'weird'
    def invalid = URIUtils.lazyInvalidUrl(raw)

    then:
    invalid.path() == null
    invalid.toString() == raw
    invalid.get() == raw
    invalid.length() == raw.length()
    invalid.hashCode() == raw.hashCode()
    invalid.subSequence(1,3) == 'ei'
    invalid.charAt(3) == 'r' as char
  }

  def "test safeConcat for code coverage"() {
    setup:
    def concat = URIUtils.safeConcat(first, second)
    expect:
    (concat == null && expected == null) || concat.toString() == expected
    where:
    first                                    | second                                   | expected
    "http://localhost/"                      | "test/me"                                | "http://localhost/test/me"
    "http://localhost"                       | "/test/me"                               | "http://localhost/test/me"
    "http://localhost/"                      | "http://localhost/test/me"               | "http://localhost/test/me"
    null                                     | null                                     | null
    null                                     | "/test"                                  | "/test"
    "http://localhost"                       | null                                     | "http://localhost/"
  }
}
