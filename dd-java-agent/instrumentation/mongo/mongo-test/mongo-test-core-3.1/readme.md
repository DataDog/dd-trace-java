This is a test-only project. There is no new instrumentation here.

Mongo has several client libraries and they can not be selectively used in one project.

This project is running tests using the `mongodb-driver` and `mongodb-driver-core`, version 3.1-3.6. In Mongo 3.7 the client APIs have changed and need to be tested in a separate project `driver-3.7-core-test`.
