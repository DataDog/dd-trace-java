package datadog.trace.util


import datadog.trace.test.util.DDSpecification

class StringsTest extends DDSpecification {

  def "test resource name from class"() {
    when:
    String s = Strings.getResourceName(className)
    then:
    s == expected
    where:
    // spotless:off
    className       | expected
    "foo.bar.Class" | "foo/bar/Class.class"
    "Class"         | "Class.class"
    // spotless:on
  }

  def "test class name from resource"() {
    when:
    String s = Strings.getClassName(resourceName)
    then:
    s == expected
    where:
    // spotless:off
    resourceName          | expected
    "foo/bar/Class.class" | "foo.bar.Class"
    "Class.class"         | "Class"
    // spotless:on
  }

  def "test package name from class"() {
    when:
    String s = Strings.getPackageName(className)
    then:
    s == expected
    where:
    // spotless:off
    className       | expected
    "foo.bar.Class" | "foo.bar"
    "Class"         | ""
    // spotless:on
  }

  def "test envvar from property"() {
    expect:
    "FOO_BAR_QUX" == Strings.toEnvVar("foo.bar-qux")
  }

  def "test join strings"() {
    when:
    String s = Strings.join(joiner, strings)
    then:
    s == expected
    where:
    // spotless:off
    joiner | strings         | expected
    ","    | ["a", "b", "c"] | "a,b,c"
    ","    | ["a", "b"]      | "a,b"
    ","    | ["a"]           | "a"
    ","    | []              | ""
    // spotless:on
  }

  def "test join strings varargs"() {
    when:
    // apparently groovy doesn't like this but it runs as if it's java
    String s = Strings.join(joiner, strings.toArray(new CharSequence[0]))
    then:
    s == expected
    where:
    // spotless:off
    joiner | strings         | expected
    ","    | ["a", "b", "c"] | "a,b,c"
    ","    | ["a", "b"]      | "a,b"
    ","    | ["a"]           | "a"
    ","    | []              | ""
    // spotless:on
  }

  def "test replace strings"() {
    when:
    String replacedAll = Strings.replace(string, delimiter, replacement)
    String replacedFirst = Strings.replaceFirst(string, delimiter, replacement)

    then:
    replacedAll == expected
    replacedFirst == expectedFirst

    where:
    // spotless:off
    string       | delimiter | replacement | expected          | expectedFirst
    "teststring" | "tstr"    | "a"         | "tesaing"         | "tesaing"
    "teststring" | "t"       | "a"         | "aesasaring"      | "aeststring"
    "teststring" | "t"       | ""          | "essring"         | "eststring"
    "teststring" | "z"       | "s"         | "teststring"      | "teststring"
    "tetetetete" | "t"       | "te"        | "teeteeteeteetee" | "teetetetete"
    "tetetetete" | "te"      | "t"         | "ttttt"           | "ttetetete"
    "tetetetete" | "tet"     | "e"         | "eeeete"          | "eetetete"
    // spotless:on
  }

  def "test escape javascript"() {
    when:
    String escaped = Strings.escapeToJson(string)

    then:
    escaped == expected

    where:
    string                   | expected
    null                     | ""
    ""                       | ""
    ((char) 4096).toString() | '\\u1000'
    ((char) 256).toString()  | '\\u0100'
    ((char) 128).toString()  | '\\u0080'
    "\b"                     | "\\b"
    "\t"                     | "\\t"
    "\n"                     | "\\n"
    "\f"                     | "\\f"
    "\r"                     | "\\r"
    '"'                      | '\\"'
    '\''                     | '\\\''
    '/'                      | '\\/'
    '\\'                     | '\\\\'
    "\u000b"                 | "\\u000B"
    "a"                      | "a"
  }

  def "test sha256"() {
    when:
    String sha256 = Strings.sha256(input)

    then:
    sha256 == expected

    where:
    input                                            | expected
    'the quick brown fox jumps over the lazy dog'    | '05c6e08f1d9fdafa03147fcb8f82f124c76d2f70e3d989dc8aadb5e7d7450bec'
    'det kommer bli bättre, du kommer andas lättare' | '9e6215a16fc8968bf3ba29d81f028f7d4bbf22ccc59ae87a0e36a8085f1c2968'
  }

  def "test truncate"() {
    when:
    String truncated = Strings.truncate(input, limit)

    then:
    truncated == expected

    where:
    input         | limit | expected
    null          | 4     | null
    ""            | 4     | ""
    "hi"          | 4     | "hi"
    "hélló"       | 5     | "hélló"
    "hélló wórld" | 5     | "hélló"
  }

  def "test map toJson: #input"() {
    when:
    String json = Strings.toJson((Map) input)

    then:
    json == expected

    where:
    input                                   | expected
    null                                    | "{}"
    new HashMap<>()                         | "{}"
    ['key1': 'value1']                      | "{\"key1\":\"value1\"}"
    ['key1': 'value1', 'key2': 'value2']    | "{\"key1\":\"value1\",\"key2\":\"value2\"}"
    ['key1': 'va"lu"e1', 'ke"y2': 'value2'] | "{\"key1\":\"va\\\"lu\\\"e1\",\"ke\\\"y2\":\"value2\"}"
  }

  def "test iterable toJson: #input"() {
    when:
    String json = Strings.toJson((Iterable) input)

    then:
    json == expected

    where:
    input                  | expected
    null                   | "[]"
    new ArrayList<>()      | "[]"
    ['value1']             | "[\"value1\"]"
    ['value1', 'value2']   | "[\"value1\",\"value2\"]"
    ['va"lu"e1', 'value2'] | "[\"va\\\"lu\\\"e1\",\"value2\"]"
  }

  def "test isNotBlank: #input"() {
    when:
    def notBlank = Strings.isNotBlank(input)

    then:
    notBlank == expected

    where:
    input        | expected
    null         | false
    ""           | false
    " "          | false
    "\t"         | false
    "\n"         | false
    " \t\n "     | false
    "a"          | true
    " a "        | true
    "\n\t123 "   | true
    " 測 試    " | true
    "   𐢀𐢀𐢀𐢀"    | true
  }
}
