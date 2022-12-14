class ExtraClass {
  fun f1(value: Int): Int {
    return value
  }

  fun f2(value: Int): Int {
    return value
  }
}
class CapturedSnapshot302 {
  companion object {
    fun main(arg: String): Int {
      val c = ExtraClass()
      return c.f1(31) + c.f2(17)
    }
  }
}
