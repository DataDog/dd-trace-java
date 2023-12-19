package com.datadog.iast.util

import com.datadog.iast.sensitive.SensitiveHandler
import spock.lang.Specification

class RangedDequeueTest extends Specification {

  void 'test tokenizer based deque'() {
    given:
    final tokenizer = Mock(SensitiveHandler.Tokenizer)
    final ranged = Mock(Ranged)

    when:
    final dequeue = RangedDeque.forTokenizer(tokenizer)

    then:
    1 * tokenizer.next() >> true
    1 * tokenizer.current() >> ranged
    !dequeue.empty

    when:
    final result = dequeue.poll()

    then:
    result == ranged
    1 * tokenizer.next() >> false
    0 * _
    dequeue.empty

    when:
    final empty = dequeue.poll()

    then:
    1 * tokenizer.next() >> false
    0 * _
    empty == null
    dequeue.empty

    when:
    dequeue.addFirst(ranged)

    then:
    !dequeue.empty

    when:
    final newItem = dequeue.poll()

    then:
    1 * tokenizer.next() >> false
    0 * _
    newItem == ranged
    dequeue.empty
  }

  void 'test array based deque'() {
    given:
    final ranged = Stub(Ranged)
    final array = [ranged] as Ranged[]

    when:
    final dequeue = RangedDeque.forArray(array)

    then:
    !dequeue.empty

    when:
    final result = dequeue.poll()

    then:
    result == ranged
    dequeue.empty

    when:
    final empty = dequeue.poll()

    then:
    empty == null
    dequeue.empty

    when:
    dequeue.addFirst(ranged)

    then:
    !dequeue.empty

    when:
    final newItem = dequeue.poll()

    then:
    newItem == ranged
    dequeue.empty
  }

  void 'test empty dequeue'() {
    given:
    final dequeue = RangedDeque.forArray(null)

    when:
    final result = dequeue.poll()

    then:
    result == null
    dequeue.empty
  }
}
