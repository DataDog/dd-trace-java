package org.example

import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec

object MyTag      extends Tag("a-custom-tag")
object MyOtherTag extends Tag("another-custom-tag")

class TestSucceedFlatSpec extends AnyFlatSpec {
  "2 + 2" should "be equal to 4" taggedAs MyTag in {
    assert(2 + 2 === 4)
  }

  it should "assert something" taggedAs MyOtherTag in {
    assert(2 + 2 > 3)
  }
}
