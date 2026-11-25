package datadog.trace.civisibility.coverage.probe.fixtures

/** Transforms an int. */
interface IntTransformer {
  fun transform(value: Int): Int
}

/**
 * Creates an object around a crossinline lambda. Kotlin copies the object's class into each
 * caller, named after the caller, with this file as its source file.
 */
inline fun transformer(crossinline block: (Int) -> Int): IntTransformer = object : IntTransformer {
  override fun transform(value: Int): Int {
    val result = block(value)
    return result + 1
  }
}
