class CapturedSnapshot201 {

  def f1(int value) {
    value // beae1817-f3b0-4ea8-a74f-000000000001
  }

  def f2(int value) {
    value
  }

  static def main(String arg) {
    def c = new CapturedSnapshot201()
    c.f1(31) + c.f2(17)
  }
}
