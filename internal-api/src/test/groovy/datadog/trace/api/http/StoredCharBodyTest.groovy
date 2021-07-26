package datadog.trace.api.http

import spock.lang.Specification

class StoredCharBodyTest extends Specification {
  StoredBodyListener listener = Mock()
  StoredCharBody storedCharBody = new StoredCharBody(listener)

  void 'basic test with no buffer extension'() {
    when:
    storedCharBody.appendData('a')

    then:
    1 * listener.onBodyStart(storedCharBody)

    when:
    storedCharBody.appendData((int) 'a')
    storedCharBody.appendData(['a' as char]* 126 as char[], 0, 126)
    storedCharBody.maybeNotify()

    then:
    1 * listener.onBodyEnd(storedCharBody)
    storedCharBody.get() == 'a' * 128
  }

  void 'has a cutoff at 2 MB'() {
    when:
    storedCharBody.appendData('a')
    storedCharBody.appendData('a' * (1024 * 1024)) // last ignored
    storedCharBody.appendData((int) 'a') // ignored
    storedCharBody.appendData('a') // ignored
    storedCharBody.appendData(['a' as char] as char[], 0, 1) // ignored

    then:
    1 * listener.onBodyStart(storedCharBody)
  }

  void 'insert invalid data'() {
    when:
    storedCharBody.appendData(-1)

    then:
    storedCharBody.get() == ''
  }

  void 'insert empty range'() {
    when:
    storedCharBody.appendData([] as char[], 0, 0)

    then:
    storedCharBody.get() == ''
  }

  void 'exercise maybeNotify and get on empty object'() {
    when:
    storedCharBody.maybeNotify()

    then:
    1 * listener.onBodyStart(storedCharBody)
    then:
    1 * listener.onBodyEnd(storedCharBody)
    then:
    storedCharBody.get() == ''
  }
}
