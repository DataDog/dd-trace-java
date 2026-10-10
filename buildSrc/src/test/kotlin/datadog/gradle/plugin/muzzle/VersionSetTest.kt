package datadog.gradle.plugin.muzzle

import org.eclipse.aether.version.Version
import org.junit.jupiter.api.Test
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat

class VersionSetTest {

  @Test
  fun `parse versions properly`() {
    data class Case(
      val version: Version,
      val versionNumber: Long,
      val ending: String
    )

    val cases = listOf(
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
      assertThat(parsed.versionNumber).withFailMessage("versionNumber for ${c.version}").isEqualTo(c.versionNumber)
      assertThat(parsed.ending).withFailMessage("ending for ${c.version}").isEqualTo(c.ending)
      assertThat(parsed.majorMinor.toLong())
        .withFailMessage("majorMinor for ${c.version}")
        .isEqualTo(c.versionNumber shr 12)
    }
  }

  @Test
  fun `select low and high from major minor`() {
    val versionsCases = listOf(
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

    val expectedCases = listOf(
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
      assertThat(versionSet.lowAndHighForMajorMinor).isEqualTo(expected)
    }
  }

  @Test
  fun `eligible boundaries match eager filtering including equivalent repository spellings`() {
    val random = Random(48)
    val versions = (0..3).flatMap { minor -> (0..7).map { patch -> ver("1.$minor.$patch") } } +
      listOf(ver("1.2.7-foo"), ver("1.2.7.foo"), ver("1.3.7-foo"), ver("1.3.7.foo"))

    repeat(100) {
      val shuffled = versions.shuffled(random)
      val eligible = shuffled.filter { random.nextBoolean() }.toSet()
      val checked = mutableListOf<Version>()
      val actual = VersionSet(shuffled).sampleEligibleBoundaries(24, Random(17)) {
        checked.add(it)
        it in eligible
      }
      val expected = VersionSet(shuffled.filter { it in eligible }).lowAndHighForMajorMinor.toMutableSet()
      // Maven ordering may differ from parsed major/minor ordering; retain both range endpoints.
      eligible.minOrNull()?.let(expected::add)
      eligible.maxOrNull()?.let(expected::add)

      assertThat(actual).containsExactlyInAnyOrderElementsOf(expected)
      assertThat(checked).doesNotHaveDuplicates()
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
