class CapturedSnapshot201 {

  def f1(int value) {
    value
  }

  def f2(int value) {
    value
  }

  static def main(String arg) {
    def c = new CapturedSnapshot201()
    c.f1(31) + c.f2(17)
  }
}
