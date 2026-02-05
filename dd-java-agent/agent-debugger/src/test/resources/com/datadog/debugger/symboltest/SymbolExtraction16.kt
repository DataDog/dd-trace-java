package com.datadog.debugger.symboltest;

class SymbolExtraction16 {

  fun f1(value: Int): Int {
    return value // beae1817-f3b0-4ea8-a74f-000000000001
  }

  fun f2(value: Int): Int {
    (1..3)
      // filter in positive
      .filter { it > 0 }
      // filter out negative
      .filterNot { it < 0 }
      // print numbers
      .forEach { println(it) }
    return value
  }

  companion object {
    fun main(arg: String): Int {
      val c = SymbolExtraction16()
      return c.f1(31) + c.f2(17)
    }
  }
}
