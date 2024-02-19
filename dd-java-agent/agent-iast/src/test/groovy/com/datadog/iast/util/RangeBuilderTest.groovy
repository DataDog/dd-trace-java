package com.datadog.iast.util

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.taint.Ranges
import spock.lang.Specification

class RangeBuilderTest extends Specification {

  void 'test empty builder'() {
    given:
    final builder = new RangeBuilder()

    when:
    final size = builder.size()

    then:
    size == 0
    builder.empty
    builder.toArray() === Ranges.EMPTY
  }

  void 'test over capacity'() {
    given:
    final builder = new RangeBuilder(1)
    builder.add(range(0))

    when:
    final addOne = builder.add(range(1))

    then:
    !addOne

    when:
    final addRange = builder.add([range(1)] as Range[])

    then:
    !addRange
  }

  void 'test single element builder'() {
    given:
    final maxSize = 8
    final arrayChunkSize = 5
    final builder = new RangeBuilder(maxSize, arrayChunkSize)

    when: 'add single element to the head'
    builder.add(range(0)) // 0

    then: 'single optimized entry is created'
    builder.size() == 1

    builder.head instanceof RangeBuilder.SingleEntry
    builder.head.size() == 1

    builder.tail == builder.head

    with(builder.toArray()) {
      assert it.length == 1
      it[0].start == 0
    }

    when: 'add elements up to the max chunk'
    (1..arrayChunkSize).each { builder.add(range(it)) } // 1, 2, 3, 4, 5

    then: 'elements are appended to the builder'
    builder.size() == arrayChunkSize + 1

    builder.head instanceof RangeBuilder.SingleEntry
    builder.head.size() == 1

    builder.tail instanceof RangeBuilder.ArrayEntry
    builder.tail.size() == arrayChunkSize
    builder.tail !== builder.head

    with(builder.toArray()) {
      assert it.length == arrayChunkSize + 1
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4, 5
    }

    when: 'add elements again up to the chunk size'
    (0..<arrayChunkSize).each { builder.add(range(it + arrayChunkSize + 1)) } // 6, 7, 8, 9, 10

    then: 'some elements must be discarded'
    assert builder.size() == maxSize: 'adding one by one we should not go over threshold'

    builder.head instanceof RangeBuilder.SingleEntry

    builder.head.next instanceof RangeBuilder.ArrayEntry

    builder.tail instanceof RangeBuilder.ArrayEntry
    builder.tail.size() == (maxSize - arrayChunkSize - 1)
    builder.tail !== builder.head
    builder.tail !== builder.head.next
    builder.tail === builder.head.next.next

    with(builder.toArray()) {
      assert it.length == maxSize: 'the builder should honor the max size'
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4, 5, 6, 7
    }
  }

  void 'test array builder'() {
    given:
    final maxSize = 8
    final arrayChunkSize = 5
    final builder = new RangeBuilder(maxSize, arrayChunkSize)

    when: 'add initial array of chunk size'
    builder.add(asArray((0..<arrayChunkSize).collect { range(it) })) // 0, 1, 2, 3, 4

    then: 'initial array is created'
    !builder.empty

    builder.head instanceof RangeBuilder.FixedArrayEntry
    builder.head.size() == arrayChunkSize

    builder.tail == builder.head

    with(builder.toArray()) {
      assert it.length == arrayChunkSize
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4
    }

    when: 'add array again up to the chunk size'
    builder.add(asArray((0..<arrayChunkSize).collect { range(it + arrayChunkSize) })) // 5, 6, 7, 8, 9

    then: 'second array is created reusing the arrays'
    assert builder.size() > maxSize: 'adding arrays we can go over threshold'
    !builder.empty

    builder.head instanceof RangeBuilder.FixedArrayEntry
    builder.head.size() == arrayChunkSize

    builder.tail instanceof RangeBuilder.FixedArrayEntry
    assert builder.tail.size() == arrayChunkSize: 'arrays should not be chunked when inserted'

    with(builder.toArray()) {
      assert it.length == maxSize: 'the builder should honor the max size'
      it.eachWithIndex { range, index -> assert range.start == index }  // 0, 1, 2, 3, 4, 5, 6, 7
    }
  }

  void 'test array builder with offset'() {
    given:
    final maxSize = 8
    final arrayChunkSize = 5
    final builder = new RangeBuilder(maxSize, arrayChunkSize)

    when: 'add initial array of chunk size without offset'
    builder.add(asArray((0..<arrayChunkSize).collect { range(it) })) // 0, 1, 2, 3, 4

    then: 'initial array is created'
    !builder.empty

    builder.head instanceof RangeBuilder.FixedArrayEntry
    builder.head.size() == arrayChunkSize

    builder.tail === builder.head

    with(builder.toArray()) {
      assert it.length == arrayChunkSize
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4
    }

    when:  'add array again up to the chunk size with offset'
    builder.add(asArray((0..<arrayChunkSize).collect { range(it) }), arrayChunkSize) // 5, 6, 7, 8, 9

    then: 'second array is created reusing the arrays'
    assert builder.size() > maxSize: 'adding arrays we can go over threshold'
    !builder.empty

    builder.head instanceof RangeBuilder.FixedArrayEntry
    builder.head.size() == arrayChunkSize

    builder.tail instanceof RangeBuilder.FixedArrayEntry
    assert builder.tail.size() == arrayChunkSize: 'arrays should not be chunked when inserted'

    with(builder.toArray()) {
      assert it.length == maxSize: 'the builder should honor the max size'
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4, 5, 6, 7
    }
  }

  void 'test mixed elements'() {
    given:
    final maxSize = 8
    final arrayChunkSize = 5
    final builder = new RangeBuilder(maxSize, arrayChunkSize)

    when:
    (0..<3).each { builder.add(range(it)) } // 0 (single), [1, 2] (array)
    builder.add(asArray((3..<5).collect { range(it) })) // [3, 4] (compacted into previous array)
    (5..<10).each { builder.add(range(it)) } // [5] (compacted into previous], [6, 7] (array), [8, 9] (skipped)

    then:
    final head = builder.head
    head instanceof RangeBuilder.SingleEntry
    head.size() == 1

    final next = builder.head.next
    next instanceof RangeBuilder.ArrayEntry
    next.size() == 5

    final tail = next.next
    tail === builder.tail
    tail instanceof RangeBuilder.ArrayEntry
    tail.size() == 2

    builder.toArray().length == maxSize
    with(builder.toArray()) {
      assert it.length == maxSize: 'the builder should honor the max size'
      it.eachWithIndex { range, index -> assert range.start == index } // 0, 1, 2, 3, 4, 5, 6, 7
    }
  }

  void 'test single entry'() {
    when:
    final entry = new RangeBuilder.SingleEntry(range(0))

    then:
    entry.size() == 1
    with(entry.toArray()) {
      assert it.size() == 1
      assert it[0] === entry.range
    }

    when:
    final target = [null, range(1)] as Range[]
    entry.arrayCopy(target, 0)

    then:
    target[0] === entry.range

    when: 'copy before zero'
    final copied = entry.arrayCopy(target, -1)

    then:
    copied == 0

    when: 'copy after length'
    final copied2 = entry.arrayCopy(target, target.length)

    then:
    copied2 == 0
  }

  void 'test array entry'() {
    when:
    final entry = new RangeBuilder.ArrayEntry(4)
    entry.add(range(0))

    then:
    entry.size() == 1
    with(entry.toArray()) {
      assert it.size() == 1
      assert it[0] === entry.ranges[0]
    }


    when:
    entry.add(range(1))

    then:
    entry.size() == 2
    with(entry.toArray()) {
      assert it.size() == 2
      assert it[0] === entry.ranges[0]
      assert it[1] === entry.ranges[1]
    }

    when:
    final target = [null, null, range(1)] as Range[]
    entry.arrayCopy(target, 0)

    then:
    target[0] === entry.ranges[0]
    target[1] === entry.ranges[1]

    when: 'fill the array'
    entry.add(range(2))
    entry.add(range(3))

    then:
    entry.toArray() === entry.ranges

    when:
    final target2 = [range(-1), null, null, null, null] as Range[]
    entry.arrayCopy(target2, 1)

    then:
    target2[1] === entry.ranges[0]
    target2[2] === entry.ranges[1]
    target2[3] === entry.ranges[2]
    target2[4] === entry.ranges[3]

    when: 'copy before zero'
    final copied = entry.arrayCopy(target, -1)

    then:
    copied == 0

    when: 'copy after length'
    final copied2 = entry.arrayCopy(target, target.length)

    then:
    copied2 == 0
  }

  void 'test fixed entry'() {
    when:
    final entry = new RangeBuilder.FixedArrayEntry([range(0), range(1)] as Range[], 0)

    then:
    entry.size() == 2
    entry.toArray() === entry.ranges

    when:
    final target = [null, null, range(2)] as Range[]
    entry.arrayCopy(target, 0)

    then:
    target[0] === entry.ranges[0]
    target[1] === entry.ranges[1]

    when: 'copy before zero'
    final copied = entry.arrayCopy(target, -1)

    then:
    copied == 0

    when: 'copy after length'
    final copied2 = entry.arrayCopy(target, target.length)

    then:
    copied2 == 0
  }

  void 'test fixed entry with offset'() {
    when:
    final entry = new RangeBuilder.FixedArrayEntry([range(1), range(2)] as Range[], 10)

    then:
    entry.size() == 2
    with(entry.toArray()) {
      assert it.size() == 2
      assert it[0].start == 11
      assert it[1].start == 12
    }

    when:
    final target = [range(10), null, null] as Range[]
    entry.arrayCopy(target, 1)

    then:
    target[0].start === 10
    target[1].start === 11
    target[2].start === 12

    when: 'copy before zero'
    final copied = entry.arrayCopy(target, -1)

    then:
    copied == 0

    when: 'copy after length'
    final copied2 = entry.arrayCopy(target, target.length)

    then:
    copied2 == 0
  }

  static Range range(final int start) {
    return new Range(start, 1, new Source((byte) 0, "name-${start}".toString(), "value-${start}".toString()), 0)
  }

  static Range[] asArray(final Collection<Range> ranges) {
    return (Range[]) ranges.toArray(new Range[0])
  }
}
