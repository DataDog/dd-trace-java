package org.example

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TestSucceedParameterizedKotlin(
  private val param1: ParamObject,
  private val param2: String,
  private val param3: Int,
) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{1}")
    fun data(): Collection<Array<Any>> =
      listOf(
        arrayOf(ParamObject(), "str1", 0),
        arrayOf(ParamObject(), "str2", 1),
      )
  }

  @Test
  fun `single document (without provider) deserialized from json`() {
    assertTrue(true)
  }

  class ParamObject
}
