package org.example

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

class TestFailedSuite extends AnyFunSuite with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    throw new RuntimeException("suite setup failed")
  }

  test("Example.add adds two numbers") {
    assert(2 + 2 === 4)
  }
}
