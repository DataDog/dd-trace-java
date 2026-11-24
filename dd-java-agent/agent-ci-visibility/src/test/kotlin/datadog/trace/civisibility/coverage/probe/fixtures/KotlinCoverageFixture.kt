package datadog.trace.civisibility.coverage.probe.fixtures

import java.io.Closeable
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * Kotlin code shapes for the line coverage tests. Every scenario runs a different set of lines.
 * Code inlined from other files keeps the line numbers the Kotlin compiler gave it.
 */
object KotlinCoverageFixture {

  const val SCENARIOS = 44

  private val lazyValue: String by lazy {
    val base = "computed"
    base.uppercase()
  }

  @JvmStatic
  fun run(scenario: Int): Any? = when (scenario) {
    0 -> classify(5)
    1 -> classify(-5)
    2 -> classify(0)
    3 -> describe("a")
    4 -> describe(null)
    5 -> describe("other")
    6 -> length(null)
    7 -> length("four")
    8 -> forceLength(null)
    9 -> Point(1, 2).copy(y = 5).toString()
    10 -> Point(1, 2) == Point(1, 2)
    11 -> destructure(Point(3, 4))
    12 -> greet()
    13 -> greet("custom", 3)
    14 -> listOf(1, 2, 3, 4).map { it * 2 }.filter { it > 2 }.sum()
    15 -> listOf(1, 0).map { 10 / it }
    16 -> scoped("value")
    17 -> useResource(false)
    18 -> useResource(true)
    19 -> tryExpression("12")
    20 -> tryExpression("x")
    21 -> ranges(10)
    22 -> Registry.register("a") + Registry.count
    23 -> lazyValue.length
    24 -> shapeArea(Shape.Circle(2.0))
    25 -> shapeArea(Shape.Square(3.0))
    26 -> "text".shout()
    27 -> localFunction(4)
    28 -> requirePositive(3)
    29 -> requirePositive(-3)
    30 -> firstNegative(listOf(3, -1, 2))
    31 -> firstNegative(listOf(1, 2))
    32 -> sequenceOf3().take(2).toList()
    33 -> sequenceOf3().toList()
    34 -> coroutine(2)
    35 -> coroutine(-1)
    36 -> LateHolder().readLate()
    37 -> sumAll(1, 2, 3)
    38 -> Meters(3).doubled().value
    39 -> timed { classify(7) }
    40 -> buildText(3)
    41 -> Counter.create().id + Counter.create().id
    42 -> grade(85)
    43 -> grade(30)
    else -> throw IllegalArgumentException("no scenario $scenario")
  }

  private fun classify(x: Int): String {
    val result: String
    if (x > 0) {
      result = "positive"
    } else if (x < 0) {
      result = "negative"
    } else {
      result = "zero"
    }
    return result
  }

  private fun describe(value: String?): String = when (value) {
    null -> "nothing"

    "a" -> "letter a"

    else -> {
      val upper = value.uppercase()
      "other $upper"
    }
  }

  private fun length(value: String?): Int {
    val actual = value ?: return -1
    return actual.length
  }

  private fun forceLength(value: String?): Int = value!!.length

  private fun destructure(point: Point): Int {
    val (x, y) = point
    return x * 10 + y
  }

  private fun greet(name: String = "world", times: Int = 1): String {
    val text = StringBuilder()
    for (i in 0 until times) {
      text.append("hi ").append(name)
    }
    return text.toString()
  }

  private fun scoped(value: String): Int {
    val length =
      value.let {
        val trimmed = it.trim()
        trimmed.length
      }
    return value.run { length + this.length }
  }

  private fun useResource(throwing: Boolean): Int {
    val closed = IntArray(1)
    return try {
      Closeable { closed[0]++ }
        .use {
          check(!throwing) { "resource" }
          closed[0]
        }
    } catch (e: IllegalStateException) {
      10 + closed[0]
    }
  }

  private fun tryExpression(text: String): Int {
    val parsed =
      try {
        text.toInt()
      } catch (e: NumberFormatException) {
        -1
      } finally {
        Registry.count++
      }
    return parsed * 2
  }

  private fun ranges(n: Int): Int {
    var sum = 0
    for (i in n downTo 0 step 3) {
      sum += i
    }
    for (i in 1..n) {
      if (i % 4 == 0) {
        continue
      }
      sum -= i
    }
    return sum
  }

  private fun shapeArea(shape: Shape): Double = when (shape) {
    is Shape.Circle -> 3.0 * shape.radius * shape.radius
    is Shape.Square -> shape.side * shape.side
  }

  private fun String.shout(): String {
    val upper = uppercase()
    return "$upper!"
  }

  private fun localFunction(n: Int): Int {
    fun square(x: Int): Int = x * x
    return square(n) + 1
  }

  private fun requirePositive(x: Int): Int {
    require(x > 0) { "not positive: $x" }
    return x
  }

  private fun firstNegative(values: List<Int>): Int? {
    values.forEach {
      if (it < 0) {
        return it
      }
    }
    return null
  }

  private fun sequenceOf3(): Sequence<Int> = sequence {
    yield(1)
    yield(2)
    yield(3)
  }

  private fun coroutine(x: Int): Any? {
    var result: Any? = null
    val block: suspend () -> Int = {
      val doubled = twice(x)
      if (doubled < 0) {
        throw IllegalArgumentException("negative")
      }
      doubled + 1
    }
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.exceptionOrNull() ?: it.getOrNull() })
    return result
  }

  private suspend fun twice(x: Int): Int = suspendCoroutine { continuation ->
    continuation.resume(x * 2)
  }

  private fun sumAll(vararg values: Int): Int {
    var total = 0
    for (value in values) {
      total += value
    }
    return total
  }

  private inline fun <T> timed(block: () -> T): T {
    val start = System.nanoTime()
    val result = block()
    check(System.nanoTime() >= start)
    return result
  }

  private fun buildText(n: Int): String = buildString {
    repeat(n) {
      append(it)
      append(',')
    }
  }

  private fun grade(score: Int): String = when {
    score >= 90 -> "A"

    score in 80..89 -> "B"

    else -> {
      val missing = 80 - score
      "F by $missing"
    }
  }

  data class Point(val x: Int, val y: Int)

  sealed class Shape {
    class Circle(val radius: Double) : Shape()

    class Square(val side: Double) : Shape()
  }

  object Registry {
    var count = 0

    init {
      count = 10
    }

    fun register(name: String): Int {
      count++
      return name.length
    }
  }

  class LateHolder {
    lateinit var value: String

    fun readLate(): Int = value.length
  }

  @JvmInline
  value class Meters(val value: Int) {
    fun doubled(): Meters = Meters(value * 2)
  }

  class Counter private constructor(val id: Int) {
    companion object {
      private var created = 0

      fun create(): Counter {
        created++
        return Counter(created)
      }
    }
  }
}
