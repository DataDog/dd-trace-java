package datadog.trace.civisibility.source.index

import datadog.trace.api.civisibility.domain.Language
import spock.lang.Specification

class LanguageDetectorTest extends Specification {

  def "detect language: #clazz"() {
    setup:
    def languageDetector = new LanguageDetector()

    expect:
    languageDetector.detect(clazz) == language

    where:
    clazz                 | language
    JavaClass             | Language.JAVA
    JavaChildClass        | Language.JAVA
    JavaInterface         | Language.JAVA
    JavaChildInterface    | Language.JAVA
    JavaEnum              | Language.JAVA
    JavaAnnotation        | Language.JAVA
    GroovyClass           | Language.GROOVY
    GroovyChildClass      | Language.GROOVY
    GroovyInterface       | Language.GROOVY
    GroovyChildInterface  | Language.GROOVY
    GroovyEnum            | Language.GROOVY
    GroovyAnnotation      | Language.GROOVY
    ScalaClass            | Language.SCALA
    ScalaChildClass       | Language.SCALA
    ScalaCaseClass        | Language.SCALA
    ScalaChildCaseClass   | Language.SCALA
    ScalaObject$          | Language.SCALA
    ScalaChildObject$     | Language.SCALA
    ScalaCaseObject$      | Language.SCALA
    ScalaChildCaseObject$ | Language.SCALA
    ScalaTrait            | Language.SCALA
    ScalaChildTrait       | Language.SCALA
    KotlinClass           | Language.KOTLIN
    KotlinChildClass      | Language.KOTLIN
    KotlinInterface       | Language.KOTLIN
    KotlinChildInterface  | Language.KOTLIN
    KotlinEnum            | Language.KOTLIN
    KotlinAnnotation      | Language.KOTLIN
    KotlinObject          | Language.KOTLIN
  }
}
