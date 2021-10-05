This is a test-only project. There is no new instrumentation necessary for
mongo sync client  but the tests can not be added to eg. driver-3.4 because doing so would mean pulling incompatible client libraries.

The tests in this project exercise the instrumentations added by driver-3.1 and driver-3.4 projects using mongo sync client libraries. This means that those projects need to be added as test dependencies.
