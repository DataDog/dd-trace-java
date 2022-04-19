package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import tries.TestClassNamesTrie

class ClassNameTrieTest extends DDSpecification {

  def 'test class name "#key" mapping'() {
    when:
    int value = TestClassNamesTrie.apply(key)
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
    'company.foobar.Sixty' | 60
    'com.f'                | 7
    'com.foo.a'            | 8
    'com.foobar.b'         | 9
    'company.f'            | 10
    'company.foo.a'        | 11
    'company.foobar.S'     | 12
    'com.Two$f'            | 13
    'foobar.Two$b'         | 14
    ''                     | -1
    'O'                    | -1
    '_'                    | -1
    'On'                   | -1
    'O_'                   | -1
    'On_'                  | -1
    'OneNoMatch'           | -1
    'com.Twos'             | 7
    'com.foo.Threes'       | 8
    'com.foobar.Fives'     | 9
    'foobar.Thre'          | -1
    'foobar.Three'         | 15
    'foobar.ThreeMore'     | 15
    // spotless:on
  }

  def 'test internal name "#key" mapping'() {
    when:
    int value = TestClassNamesTrie.apply(key)
    then:
    value == expected
    where:
    // spotless:off
    key                    | expected
    'One'                  | 1
    'com/Two'              | 2
    'com/foo/Three'        | 3
    'company/foo/Four'     | 4
    'com/foobar/Five'      | 5
    'company/foobar/Six'   | 6
    'company/foobar/Sixty' | 60
    'com/f'                | 7
    'com/foo/a'            | 8
    'com/foobar/b'         | 9
    'company/f'            | 10
    'company/foo/a'        | 11
    'company/foobar/S'     | 12
    'com/Two$f'            | 13
    'foobar/Two$b'         | 14
    ''                     | -1
    'O'                    | -1
    '_'                    | -1
    'On'                   | -1
    'O_'                   | -1
    'On_'                  | -1
    'OneNoMatch'           | -1
    'com/Twos'             | 7
    'com/foo/Threes'       | 8
    'com/foobar/Fives'     | 9
    'foobar/Thre'          | -1
    'foobar/Three'         | 15
    'foobar/ThreeMore'     | 15
    // spotless:on
  }

  def 'test large trie generation'() {
    setup:
    def mapping = (0..8191).collectEntries({
      [UUID.randomUUID().toString().replace('-', '.'), it]
    }) as TreeMap<String, Integer>
    when:
    def builder = new ClassNameTrie.Builder()
    mapping.each { className, number ->
      builder.put(className, number)
    }
    def trie = builder.buildTrie()
    then:
    mapping.each({
      assert trie.apply(it.key) == it.value
    })
  }

  def 'test manual trie creation'() {
    setup:
    def data = ['\001\141\u4001', '\001\142\u4002', '\001\143\u8003',]
    when:
    def trie = ClassNameTrie.create(data as String[])
    then:
    trie.apply('a') == 1
    trie.apply('ab') == 2
    trie.apply('abc') == 3
    trie.apply('') == -1
    trie.apply('b') == -1
    trie.apply('c') == -1
  }
}
