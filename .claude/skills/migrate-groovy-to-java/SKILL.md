---
name: migrate-groovy-to-java
description: migrate test groovy files to java
---

Migrate test Groovy files to Java using JUnit 5

1. List all groovy files of the current gradle module
2. convert groovy files to Java using Junit 5
3. make sure the tests are still passing after migration
4. remove groovy files

When converting groovy code to java code make sure that:
- the Java code generated is compatible with JDK 8
- when translating Spock test, favor using `@CsvSource` with `|` delimiters
- when converting tuples always returns object array
- Instead of checking a state and throwing an exception, use JUnit asserts
- Do not wrap checked exception and throwing a Runtime exception, prefer adding a throws clause at method declaration
- Do not mark local variables `final`
