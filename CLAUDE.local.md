# Personal Context for dd-trace-java

## Experience Level in This Codebase
I have advanced knowledge of the dd-trace-java codebase and am comfortable modifying any area including instrumentation modules, agent internals, profiler, and AppSec components.

## Testing Approach in This Repo
- I write tests after implementation
- I always update tests when modifying existing code
- I prefer JUnit/Java tests over Spock/Groovy for new test files
- I always create integration tests for new instrumentations
- I test private methods indirectly through public APIs
- I use static imports for test assertions
- I use setup()/cleanup() methods for test fixtures in Spock tests

## Code Style and Documentation
- I prefer concise variable names when context is clear
- I document all public methods with Javadoc
- I use explicit null checks (if (x != null)) rather than Optional wrapping
- I prefer method references (Class::method) over lambda expressions
- I prefer early returns with guard clauses over nested conditionals
- I extract helper methods after 2-3 repetitions of a pattern
- I avoid TODO comments and complete work before committing

## Java Preferences
- I always extract magic numbers and strings to constants
- I prefer method overloading over varargs for flexible parameters
- I deprecate APIs with @Deprecated before removal for breaking changes

## Instrumentation Practices
- I delegate complex logic from @Advice methods to helper/decorator classes
- I add trace logging only for complex instrumentations
- I always consider performance implications in all code changes
