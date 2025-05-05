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

  def 'test class name "#key" mapping with fromIndex'() {
    when:
    int value = TestClassNamesTrie.apply(key, "garbage.".length())
    then:
    value == expected
    where:
    // spotless:off
    key                    | expected
    'garbage.One'                  | 1
    'garbage.com.Two'              | 2
    'garbage.com.foo.Three'        | 3
    'garbage.company.foo.Four'     | 4
    'garbage.com.foobar.Five'      | 5
    'garbage.company.foobar.Six'   | 6
    'garbage.company.foobar.Sixty' | 60
    'garbage.com.f'                | 7
    'garbage.com.foo.a'            | 8
    'garbage.com.foobar.b'         | 9
    'garbage.company.f'            | 10
    'garbage.company.foo.a'        | 11
    'garbage.company.foobar.S'     | 12
    'garbage.com.Two$f'            | 13
    'garbage.foobar.Two$b'         | 14
    'garbage.'                     | -1
    'garbage.O'                    | -1
    'garbage._'                    | -1
    'garbage.On'                   | -1
    'garbage.O_'                   | -1
    'garbage.On_'                  | -1
    'garbage.OneNoMatch'           | -1
    'garbage.com.Twos'             | 7
    'garbage.com.foo.Threes'       | 8
    'garbage.com.foobar.Fives'     | 9
    'garbage.foobar.Thre'          | -1
    'garbage.foobar.Three'         | 15
    'garbage.foobar.ThreeMore'     | 15
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
    def data = '\001\141\u4001\001\142\u4002\001\143\u8003'
    when:
    def trie = new ClassNameTrie(data as char[], null)
    then:
    trie.apply('a') == 1
    trie.apply('ab') == 2
    trie.apply('abc') == 3
    trie.apply('') == -1
    trie.apply('b') == -1
    trie.apply('c') == -1
  }

  def 'test #keys trie structure'() {
    when:
    def builder = new ClassNameTrie.Builder()
    keys.each {
      builder.put(it, 1)
    }
    def trie = builder.buildTrie()
    then:
    trie.trieData == trieData.toCharArray()
    where:
    // spotless:off
    keys                                 | trieData
    ['a']                                | '\001\141\u8001'
    ['a', 'b']                           | '\002\141\142\u8001\u8001\000'
    ['b', 'a']                           | '\002\141\142\u8001\u8001\000'
    ['c', 'a', 'b']                      | '\003\141\142\143\u8001\u8001\u8001\000\000'
    ['b', 'c', 'a']                      | '\003\141\142\143\u8001\u8001\u8001\000\000'
    ['aa']                               | '\001\141\001\141\u8001'
    ['aa', 'b']                          | '\002\141\142\001\u8001\002\141\u8001'
    ['b', 'aa']                          | '\002\141\142\001\u8001\002\141\u8001'
    ['aaa', 'bb']                        | '\002\141\142\002\001\003\141\141\u8001\142\u8001'
    ['bbb', 'aa']                        | '\002\141\142\001\002\002\141\u8001\142\142\u8001'
    ['a', 'aa']                          | '\001\141\u4001\001\141\u8001'
    ['aa', 'a']                          | '\001\141\u4001\001\141\u8001'
    ['aaaaaaaa', 'aaaaa']                | '\001\141\003\141\141\141\001\141\u4001\001\141\002\141\141\u8001'
    ['aaaaa', 'aaaaaaaa']                | '\001\141\003\141\141\141\001\141\u4001\001\141\002\141\141\u8001'
    ['aaa', 'a', 'aa']                   | '\001\141\u4001\001\141\u4001\001\141\u8001'
    ['a', 'ab']                          | '\001\141\u4001\001\142\u8001'
    ['ab', 'a']                          | '\001\141\u4001\001\142\u8001'
    ['aa', 'ab']                         | '\001\141\000\002\141\142\u8001\u8001\000'
    ['ab', 'aa']                         | '\001\141\000\002\141\142\u8001\u8001\000'
    ['aaaaa', 'aaaab']                   | '\001\141\003\141\141\141\002\141\142\u8001\u8001\000'
    ['bbbbb', 'bbbba']                   | '\001\142\003\142\142\142\002\141\142\u8001\u8001\000'
    ['aaaaa', 'aaaabbbb']                | '\001\141\003\141\141\141\002\141\142\u8001\003\000\142\142\142\u8001'
    ['bbbbb', 'bbbbaaaa']                | '\001\142\003\142\142\142\002\141\142\003\u8001\004\141\141\141\u8001'
    ['aaaab', 'aaaaa']                   | '\001\141\003\141\141\141\002\141\142\u8001\u8001\000'
    ['bbbba', 'bbbbb']                   | '\001\142\003\142\142\142\002\141\142\u8001\u8001\000'
    ['aaaabbbb', 'aaaaaaa']              | '\001\141\003\141\141\141\002\141\142\002\003\003\141\141\u8001\142\142\142\u8001'
    ['bbbbaaaa', 'bbbbbbb']              | '\001\142\003\142\142\142\002\141\142\003\002\004\141\141\141\u8001\142\142\u8001'
    ['aaaabbbb', 'aaacccbb']             | '\001\141\002\141\141\002\141\143\004\004\005\142\142\142\142\u8001\143\143\142\142\u8001'
    // spotless:on
  }

  def 'trie builder allows overwriting'() {
    setup:
    def mapping = (0..128).collectEntries({
      [UUID.randomUUID().toString().replace('-', '.'), it]
    }) as TreeMap<String, Integer>
    when:
    def builder = new ClassNameTrie.Builder()
    // initial values
    mapping.each { className, number ->
      builder.put(className, number)
    }
    // update values
    mapping.each { className, number ->
      builder.put(className, (0x1000 | number))
    }
    def trie = builder.buildTrie()
    then:
    mapping.each({
      assert trie.apply(it.key) == (0x1000 | it.value)
    })
  }

  def 'trie content can be exported and re-imported'() {
    setup:
    def mapping = (0..128).collectEntries({
      [UUID.randomUUID().toString().replace('-', '.'), it]
    }) as TreeMap<String, Integer>
    when:
    def exporter = new ClassNameTrie.Builder()
    // initial values
    mapping.each { className, number ->
      exporter.put(className, number)
    }
    // export
    def sink = new ByteArrayOutputStream()
    exporter.writeTo(new DataOutputStream(sink))
    // re-import
    def source = new ByteArrayInputStream(sink.toByteArray())
    def importer = ClassNameTrie.readFrom(new DataInputStream(source))
    then:
    mapping.each({
      assert importer.apply(it.key) == it.value
    })
  }
}
