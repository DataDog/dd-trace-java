package foo.bar

import de.thetaphi.forbiddenapis.SuppressForbidden
import java.security.MessageDigest

@SuppressForbidden
class IndyInterfaceSuite {

  String init(final String value) {
    return new String("$value")
  }

  String concat(final String left, final String right) {
    return left.concat(right)
  }

  String concat1(final String left, final String right) {
    return "$left ".concat(" $right")
  }

  String concat2(final String left, final String right) {
    return "$left ".concat(right)
  }

  String concat3(final String left, final String right) {
    return left.concat(" $right")
  }

  String subSequence(final String parameter, final int start, final int end) {
    return "$parameter".subSequence(start, end)
  }

  String toUpperCase(final String parameter) {
    return "$parameter".toUpperCase()
  }

  String toLowerCase(final String parameter) {
    return "$parameter".toLowerCase()
  }

  String join(final String delimiter, final String... values) {
    return String.join("$delimiter", values)
  }

  String format(final String pattern, final String... values) {
    return String.format("$pattern", values)
  }

  String format(final Locale locale, final String pattern, final String... values) {
    return String.format(locale, "$pattern", values)
  }

  String repeat(final String value, final int count) {
    final target = "$value"
    target.metaClass.mixin(JDK11Category)
    return target.repeat(count)
  }

  String hash(final String algo, final String value) {
    final digest = MessageDigest.getInstance(algo)
    digest.update(value.bytes)
    return digest.toString()
  }
}


