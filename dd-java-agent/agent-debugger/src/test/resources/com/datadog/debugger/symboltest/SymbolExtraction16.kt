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

  fun f3(value: Int): Int {
    val list = listOf(value, 2, 3)
    val max = list.maxOf { it -> it > 0 }
    return value
  }

  fun f4(value: Int): Int {
    val set = setOf(Person("john", "doe"), Person("agent", "smith"))
    return set.groupingBy { it.firstName }.eachCount().toMutableMap().size
  }

  companion object {
    fun main(arg: String): Int {
      val c = SymbolExtraction16()
      return c.f1(31) + c.f2(17) + c.f3(23) + c.f4(0)
    }
  }
}

data class Person(val firstName: String, val lastName: String)
