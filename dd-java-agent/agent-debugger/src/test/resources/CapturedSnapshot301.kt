class CapturedSnapshot301 {

  fun f1(value: Int): Int {
    return value
  }

  fun f2(value: Int): Int {
    return value
  }

  companion object {
    fun main(arg: String): Int {
      val c = CapturedSnapshot301()
      return c.f1(31) + c.f2(17)
    }
  }
}
