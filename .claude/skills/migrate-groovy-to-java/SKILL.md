---
name: migrate-groovy-to-java
description: migrate test groovy files to java
---

Migrate test Groovy files to Java using JUnit 5

1. List all Groovy files of the current Gradle module
2. Convert Groovy files to Java using JUnit 5
3. Make sure the tests are still passing after migration and that the test count has not changed
4. Remove Groovy files

When converting Groovy code to Java code make sure that:
- The Java code generated is compatible with JDK 8
- When translating Spock tests, favor using `@CsvSource` with `|` delimiters
- When using `@MethodSource`, use the test method name and suffix it with `_arguments`
- When converting tuples, create light dedicated structure instead to keep the typing system
- Instead of checking a state and throwing an exception, use JUnit asserts
- Do not wrap checked exception and throw a Runtime exception; prefer adding a throws clause at method declaration
- Do not mark local variables `final`
