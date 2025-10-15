package datadog.trace.bootstrap

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

class InstanceStoreTest extends DDSpecification {

  @Shared
  private AtomicInteger counter = new AtomicInteger()

  // Since the InstanceStore is a per Class singleton, every test case needs new keys
  String nextKey() {
    "key-${counter.incrementAndGet()}"
  }

  def "test returns existing value"() {
    setup:
    def someStore = InstanceStore.of(Some)
    def some1 = new Some()
    def some2 = new Some()
    def key = nextKey()
    someStore.put(key, some1)

    when:
    def current = someStore.putIfAbsent(key, some2)

    then:
    current == some1
    current != some2

    when:
    current = someStore.putIfAbsent(key, some2)

    then:
    current == some1
    current != some2
  }

  def "test returns existing store"() {
    setup:
    def some1 = new Some()
    def key = nextKey()
    InstanceStore.of(Some).put(key, some1)

    when:
    def current = InstanceStore.of(Some).putIfAbsent(key, new Some())

    then:
    current == some1

    when:
    current = InstanceStore.of(Some).putIfAbsent(key, new Some())

    then:
    current == some1
  }

  def "test conditionally set first value"() {
    def someStore = InstanceStore.of(Some)
    def some1 = new Some()
    def key = nextKey()

    when:
    def current = someStore.putIfAbsent(key, some1)

    then:
    current == some1

    when:
    current = someStore.putIfAbsent(key, new Some())

    then:
    current == some1
  }

  def "test factory is not called when value exists"() {
    def someStore = InstanceStore.of(Some)
    def some1 = new Some()
    def invocations = new AtomicInteger()
    def key = nextKey()
    someStore.put(key, some1)

    when:
    def current = someStore.putIfAbsent(key, new Creator(invocations))

    then:
    current == some1
    invocations.get() == 0
  }

  def "test factory is called only once for multiple invocations"() {
    def someStore = InstanceStore.of(Some)
    def some1 = new Some()
    def invocations = new AtomicInteger()
    def key = nextKey()

    when:
    def current = someStore.putIfAbsent(key, new Creator(invocations, some1))

    then:
    current == some1
    invocations.get() == 1

    when:
    current = someStore.putIfAbsent(key, new Creator(invocations))

    then:
    current == some1
    invocations.get() == 1
  }

  static class Some {}

  static class Creator implements ContextStore.Factory<Some> {
    private AtomicInteger invocations
    private Some some

    Creator(AtomicInteger invocations, Some some) {
      this.invocations = invocations
      this.some = some
    }

    Creator(AtomicInteger invocations) {
      this(invocations, new Some())
    }

    @Override
    Some create() {
      invocations.incrementAndGet()
      return some
    }
  }
}
