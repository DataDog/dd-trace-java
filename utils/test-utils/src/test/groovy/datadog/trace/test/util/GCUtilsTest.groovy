package datadog.trace.test.util

import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class GCUtilsTest extends Specification {

  @Unroll
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  def 'awaitGC without args (with sleep #sleep ms)'() {
    given:
    Object o = new Object()
    WeakReference<?> ref = new WeakReference<>(o)

    when:
    Thread.sleep(sleep)
    o = null
    GCUtils.awaitGC()

    then:
    ref.get() == null

    where:
    sleep | _
    1     | _
    10    | _
    100   | _
    500   | _
    1000  | _
    2000  | _
  }


  @Unroll
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  def 'awaitGC with reference arg (with sleep #sleep ms)'() {
    given:
    Object o = new Object()
    WeakReference<?> ref = new WeakReference<>(o)

    when:
    Thread.sleep(sleep)
    o = null
    GCUtils.awaitGC(ref)

    then:
    ref.get() == null

    where:
    sleep | _
    1     | _
    10    | _
    100   | _
    500   | _
    1000  | _
    2000  | _
  }
}
