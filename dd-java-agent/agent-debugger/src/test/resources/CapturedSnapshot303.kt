class CapturedSnapshot303 {
  companion object {
    fun main(arg: String): Int {
      listOf(1, 2, 3, 4, 5)
        .map { it * 2 }
        .filter { it % 2 == 0 }
        .forEach { println(it) }
      return arg.length
    }
  }
}
