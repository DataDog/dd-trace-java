package org.example

import datadog.trace.api.civisibility.CIConstants.Tags
import datadog.trace.api.civisibility.{CIConstants, InstrumentationBridge}
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec

object ItrUnskippableTag extends Tag(Tags.ITR_UNSKIPPABLE_TAG)

class TestSucceedUnskippable extends AnyFlatSpec {
  "test" should "assert something" taggedAs ItrUnskippableTag in {
    assert(2 + 2 > 3)
  }
}
