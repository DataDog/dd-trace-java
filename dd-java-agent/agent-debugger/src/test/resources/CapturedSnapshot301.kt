class CapturedSnapshot301 {

  fun f1(value: Int): Int {
    return value // beae1817-f3b0-4ea8-a74f-000000000001
  }

  fun f2(value: Int): Int = value

  companion object {
    fun main(arg: String): Int {
      val c = CapturedSnapshot301()
      return c.f1(31) + c.f2(17)
    }
  }
}
