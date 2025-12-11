package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VersionSetTest {
  @Test
  fun `parse versions properly`() {
    data class Case(
      val version: Version,
      val versionNumber: Long,
      val ending: String
    )

    val cases =
      listOf(
        Case(ver("1.2.3"), num(1, 2, 3), ""),
        Case(ver("4.5.6-foo"), num(4, 5, 6), "foo"),
        Case(ver("7.8.9.foo"), num(7, 8, 9), "foo"),
        Case(ver("10.11.12.foo-bar"), num(10, 11, 12), "foo-bar"),
        Case(ver("13.14.foo-bar"), num(13, 14, 0), "foo-bar"),
        Case(ver("15.foo"), num(15, 0, 0), "foo"),
        Case(ver("16-foo"), num(16, 0, 0), "foo")
      )

    for (c in cases) {
      val parsed = VersionSet.ParsedVersion(c.version)
      assertEquals(c.versionNumber, parsed.versionNumber, "versionNumber for ${c.version}")
      assertEquals(c.ending, parsed.ending, "ending for ${c.version}")
      assertEquals(
        c.versionNumber shr 12,
        parsed.majorMinor.toLong(),
        "majorMinor for ${c.version}"
      )
    }
  }

  @Test
  fun `select low and high from major minor`() {
    val versionsCases =
      listOf(
        listOf(
          ver("4.5.6"),
          ver("1.2.3")
        ),
        listOf(
          ver("1.2.3"),
          ver("1.2.1"),
          ver("1.3.0"),
          ver("1.2.7"),
          ver("1.4.17"),
          ver("1.4.1"),
          ver("1.4.0"),
          ver("1.4.10")
        )
      )

    val expectedCases =
      listOf(
        listOf(
          ver("1.2.3"),
          ver("4.5.6")
        ),
        listOf(
          ver("1.2.1"),
          ver("1.2.7"),
          ver("1.3.0"),
          ver("1.4.0"),
          ver("1.4.17")
        )
      )

    versionsCases.zip(expectedCases).forEach { (versions, expected) ->
      val versionSet = VersionSet(versions)
      assertEquals(expected, versionSet.lowAndHighForMajorMinor)
    }
  }

  private fun ver(v: String): Version = TestVersion(v)

  private fun num(major: Int, minor: Int, micro: Int): Long {
    var result = major.toLong()
    result = (((result shl 12) + minor) shl 12) + micro
    return result
  }

  private class TestVersion(private val v: String) : Version {
    override fun compareTo(other: Version?): Int {
      if (other is TestVersion) {
        return v.compareTo(other.v)
      }

      return 1
    }

    override fun equals(other: Any?): Boolean = other is TestVersion && v == other.v

    override fun hashCode(): Int = v.hashCode()

    override fun toString(): String = v
  }
}
