package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestIgnored extends AnyFunSuite {
  ignore("Example.add adds two numbers") {
    assert(2 + 2 === 42)
  }
}
