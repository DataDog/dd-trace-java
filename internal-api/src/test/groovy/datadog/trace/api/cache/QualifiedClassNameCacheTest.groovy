package datadog.trace.api.cache

import datadog.trace.api.Functions
import datadog.trace.test.util.DDSpecification

import java.util.function.Function

class QualifiedClassNameCacheTest extends DDSpecification {

  def "test cached string operations"() {
    when:
    QualifiedClassNameCache cache = new QualifiedClassNameCache(new Function<Class<?>, String>() {
        @Override
        String apply(Class<?> input) {
          return input.getSimpleName()
        }
      }, func)
    String qualified = cache.getQualifiedName(type, prefix)

    then:
    qualified == expected
    where:
    type   | prefix | func                                                | expected
    String | "foo." | Functions.SuffixJoin.ZERO                           | "foo.String"
    String | ".foo" | Functions.PrefixJoin.ZERO                           | "String.foo"
    String | "foo"  | Functions.SuffixJoin.of(".")                        | "foo.String"
    String | "foo"  | Functions.PrefixJoin.of(".")                        | "String.foo"
    String | "foo"  | Functions.SuffixJoin.of(".", new Replace("oo", "")) | "f.String"
    String | "foo"  | Functions.PrefixJoin.of(".", new Replace("oo", "")) | "String.f"
  }

  def "test get cached class name"() {
    when:
    QualifiedClassNameCache cache = new QualifiedClassNameCache(new Function<Class<?>, CharSequence>() {
        @Override
        CharSequence apply(Class<?> input) {
          return input.getSimpleName()
        }
      }, Functions.PrefixJoin.ZERO)
    then:
    cache.getClassName(type) == expected

    where:
    type             | expected
    String           | "String"
    Functions.Suffix | "Suffix"
  }

  class Replace implements Function<String, String> {
    private final String find
    private final String replace

    Replace(String find, String replace) {
      this.find = find
      this.replace = replace
    }

    @Override
    String apply(String input) {
      return input.replace(find, replace)
    }
  }
}
