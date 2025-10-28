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
    className             | expected
    "foo.bar.Class"       | "foo/bar/Class.class"
    "foo/bar/Class.class" | "foo/bar/Class.class"
    "Class"               | "Class.class"
    "Class.class"         | "Class.class"
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
    "foo.bar.Class"       | "foo.bar.Class"
    "Class.class"         | "Class"
    "Class"               | "Class"
    // spotless:on
  }

  def "test internal name from class name"() {
    when:
    String s = Strings.getInternalName(resourceName)
    then:
    s == expected
    where:
    // spotless:off
    resourceName    | expected
    "foo.bar.Class" | "foo/bar/Class"
    "Class"         | "Class"
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
    "FOO_BAR_QUX" == ConfigStrings.toEnvVar("foo.bar-qux")
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

  def "test isNotBlank and isBlank: #input"() {
    when:
    def notBlank = Strings.isNotBlank(input)
    def isBlank = Strings.isBlank(input)

    then:
    notBlank == expected
    isBlank == !notBlank

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

  void 'test hexadecimal encoding of #value'() {
    when:
    def encoded = Strings.toHexString(value?.bytes)

    then:
    if (value == null) {
      encoded == expected
    } else {
      encoded.equalsIgnoreCase(expected)
    }

    where:
    value                   | expected
    null                    | null
    ''                      | ''
    'zouzou@sansgluten.com' | '7A6F757A6F754073616E73676C7574656E2E636F6D'
  }

  void 'test coalesce: #first - #second'() {
    when:
    def combined = Strings.coalesce(first, second)

    then:
    expected == combined

    where:
    first | second | expected
    "a"   | "b"    | "a"
    "a"   | null   | "a"
    null  | "b"    | "b"
    ""    | "b"    | "b"
    null  | null   | null
    ""    | ""     | null
  }
}
