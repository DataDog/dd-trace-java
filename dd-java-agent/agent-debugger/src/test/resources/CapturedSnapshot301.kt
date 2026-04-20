class CapturedSnapshot301 {

  fun f1(value: Int): Int {
    return value // beae1817-f3b0-4ea8-a74f-000000000001
  }

  fun f2(value: Int): Int {
    (1..3)
      .filter {
        it > 0
      }
      .forEach {
        println(it)
    }
    return value
  }

  fun f3(value: Int): Int {
    val list = listOf(value, 2, 3)
    val max = list.maxOf { it -> it > 0 }
    return value
  }

  companion object {
    fun main(arg: String): Int {
      val c = CapturedSnapshot301()
      return c.f1(31) + c.f2(17)
    }
  }
}
