package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import tries.TestTrie

class ClassNameTrieTest extends DDSpecification {

  def 'test trie mapping'() {
    when:
    int value = TestTrie.apply(key)
    then:
    value == expected
    where:
    // spotless:off
    key                    | expected
    'One'                  | 1
    'com.Two'              | 2
    'com.foo.Three'        | 3
    'company.foo.Four'     | 4
    'com.foobar.Five'      | 5
    'company.foobar.Six'   | 6
    'com.f'                | 7
    'com.foo.a'            | 8
    'com.foobar.b'         | 9
    'company.f'            | 10
    'company.foo.a'        | 11
    'company.foobar.S'     | 12
    'com.Two$f'            | 13
    'foobar.Two$b'         | 14
    'OneNoMatch'           | -1
    'com.Twos'             | 7
    'com.foo.Threes'       | 8
    'com.foobar.Fives'     | 9
    // spotless:on
  }
}
