import java.time.Duration

plugins {
  `java-test-fixtures`
  id("dd-trace-java.module.instrumentation")
  id("dd-trace-java.instrumentation.jdbc-tests")
  id("dd-trace-java.call-site-instrumentation")
  id("dd-trace-java.jmh-conventions")
}

muzzle {
  pass {
    coreJdk()
    extraDependency("com.ibm.db2:jcc:11.1.4.4")
    extraDependency("com.zaxxer:HikariCP:2.4.0")
  }
}

// The nested *-test modules run the same tests against old drivers and on Java 11
jdbcTests {
  latestDepSuite("latestDepTest")
}

dependencies {
  compileOnly("com.zaxxer:HikariCP:2.4.0")
}

// forkedTest takes ~1000s on CI and has started to hit the default 20m budget more often.
// Give it some headroom while collecting stats on the root cause.
tasks.named<Test>("forkedTest") {
  timeout = Duration.ofMinutes(25)
}
