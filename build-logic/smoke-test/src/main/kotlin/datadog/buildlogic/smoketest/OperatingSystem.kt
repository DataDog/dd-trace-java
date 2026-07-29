package datadog.buildlogic.smoketest

import java.util.Locale

internal fun isWindows(osName: String = System.getProperty("os.name")): Boolean =
  osName.lowercase(Locale.ROOT).contains("windows")

