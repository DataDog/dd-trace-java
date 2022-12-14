import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class CapturedSnapshot303 {

  suspend fun f1(value: Int): Int {
    delay(1)
    return value
  }

  fun f2(value: Int): Int {
    return value
  }

  companion object {
    fun main(arg: String): Int {
      val c = CapturedSnapshot303()
      val eventualA = runBlocking { c.f1(31) }
      return eventualA + c.f2(17)
    }
  }
}
