package datadog.trace.api.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.Functions;
import datadog.trace.api.Functions.PrefixJoin;
import datadog.trace.api.Functions.SuffixJoin;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class QualifiedClassNameCacheTest extends DDJavaSpecification {

  @ParameterizedTest(name = "{0}")
  @MethodSource("testCachedStringOperationsArguments")
  void testCachedStringOperations(
      String scenario, Class<?> type, String prefix, Functions.Join func, String expected) {
    QualifiedClassNameCache cache = new QualifiedClassNameCache(Class::getSimpleName, func);

    CharSequence qualified = cache.getQualifiedName(type, prefix);

    assertEquals(expected, qualified.toString());
  }

  static Stream<Arguments> testCachedStringOperationsArguments() {
    return Stream.of(
        arguments("suffix join zero", String.class, "foo.", SuffixJoin.ZERO, "foo.String"),
        arguments("prefix join zero", String.class, ".foo", PrefixJoin.ZERO, "String.foo"),
        arguments("suffix join with dot", String.class, "foo", SuffixJoin.of("."), "foo.String"),
        arguments("prefix join with dot", String.class, "foo", PrefixJoin.of("."), "String.foo"),
        arguments(
            "suffix join with transformer",
            String.class,
            "foo",
            SuffixJoin.of(".", new Replace("oo", "")),
            "f.String"),
        arguments(
            "prefix join with transformer",
            String.class,
            "foo",
            PrefixJoin.of(".", new Replace("oo", "")),
            "String.f"));
  }

  @TableTest({
    "scenario     | type                                 | expected",
    "string class | java.lang.String                     | String  ",
    "nested class | 'datadog.trace.api.Functions$Suffix' | Suffix  "
  })
  void testGetCachedClassName(Class<?> type, String expected) {
    QualifiedClassNameCache cache =
        new QualifiedClassNameCache(Class::getSimpleName, PrefixJoin.ZERO);

    assertEquals(expected, cache.getClassName(type).toString());
  }

  private static class Replace implements Function<CharSequence, CharSequence> {
    private final String find;
    private final String replace;

    Replace(String find, String replace) {
      this.find = find;
      this.replace = replace;
    }

    @Override
    public CharSequence apply(CharSequence input) {
      return input.toString().replace(find, replace);
    }
  }
}
