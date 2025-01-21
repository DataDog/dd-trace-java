class CapturedSnapshot101 {
  def f1(value: Int) = {
    value // beae1817-f3b0-4ea8-a74f-000000000001
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
