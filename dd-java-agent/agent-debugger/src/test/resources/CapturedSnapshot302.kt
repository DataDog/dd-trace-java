import kotlinx.coroutines.*

class CapturedSnapshot302 {
  var intField = 42
  var strField = "foo"
  val list: MutableList<Int> = mutableListOf()

  suspend fun process(arg: String): Int {
    println(intField)
    try {
      list.add(1)
      val content = download(arg)
      list.add(2)
      subprocess() {
        println(arg)
        println(list)
        delay(1L)
        intField = 43
        strField = "bar"
      }
      println(strField)
      println(content)
    } catch (e: Exception) {
      println(list)
      println(e)
    }
    return arg.length
  }

  suspend fun download(arg: String): String {
    delay(10L)
    return arg
  }

  suspend fun subprocess(f: suspend () -> Unit): Unit {
    println(intField)
    f()
    println(strField)
  }



  companion object {
    fun main(arg: String): Int = runBlocking {
      val c = CapturedSnapshot302()
      c.process(arg)
    }
  }
}
