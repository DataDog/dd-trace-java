class CapturedSnapshot101 {
  def f1(value: Int) = {
    value
  }
  def f2(value: Int) = {
    value
  }
}

object CapturedSnapshot101 {
  def main(arg: String) = {
    val c = new CapturedSnapshot101()
    c.f1(31) + c.f2(17)
  }
}
