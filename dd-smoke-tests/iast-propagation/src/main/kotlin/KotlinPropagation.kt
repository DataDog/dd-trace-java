import java.util.function.Supplier

class KotlinPropagation : Supplier<List<String>> {
  override fun get(): List<String> = listOf("plus", "interpolation")

  fun plus(param: String): String = param + param

  fun interpolation(param: String): String = "Interpolation $param"
}
