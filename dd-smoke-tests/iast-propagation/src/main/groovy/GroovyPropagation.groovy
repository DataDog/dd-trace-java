import java.util.function.Supplier

class GroovyPropagation implements Supplier<List<String>> {

  @Override
  List<String> get() {
    return ["plus", "interpolation"]
  }

  String plus(String tainted) {
    return tainted + tainted
  }


  String interpolation(String tainted) {
    return "interpolation: $tainted"
  }
}
