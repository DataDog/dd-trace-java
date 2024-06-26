package datadog.trace.civisibility.source.index

open class KotlinClass

class KotlinChildClass : KotlinClass()

interface KotlinInterface

interface KotlinChildInterface : KotlinInterface

data class KotlinDataClass(val p: Int)

enum class KotlinEnum

annotation class KotlinAnnotation

object KotlinObject

data object KotlinDataObject
