package org.example

import datadog.trace.api.civisibility.CIConstants.Tags
import datadog.trace.api.civisibility.{CIConstants, InstrumentationBridge}
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec

object SkipEfdTag extends Tag(Tags.EFD_DISABLE_TAG)

class TestSucceedSkipEfd extends AnyFlatSpec {
  "test" should "assert something" taggedAs SkipEfdTag in {
    assert(2 + 2 > 3)
  }
}
