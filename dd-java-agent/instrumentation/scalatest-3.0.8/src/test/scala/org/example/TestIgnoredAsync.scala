package org.example

import org.scalatest.funsuite.AsyncFunSuite

class TestIgnoredAsync extends AsyncFunSuite {
  ignore("Example.add adds two numbers") {
    assert(2 + 2 === 42)
  }
}
