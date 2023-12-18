package org.example

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class TestSucceedParameterized extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  "addition" should "correctly add two numbers" in {
    val examples = Table(
      ("a", "b", "result"),
      (1, 1, 2),
      (2, 2, 4)
    )

    forAll(examples) { (a: Int, b: Int, result: Int) =>
      assert(a + b == result)
    }
  }
}
