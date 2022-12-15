class CapturedSnapshot101 {
  def f1(cc: CC) = cc match {
    case CC(_, value, _) => value
  }
  def f2(value: Int) = {
    value
  }
}

case class CC(arg1: String, arg2: Int, arg3: Boolean)

object CapturedSnapshot101 {
  def main(arg: String) = {
    val c = new CapturedSnapshot101()
    c.f1(CC("foo", 31, true)) + c.f2(17)
  }
}
