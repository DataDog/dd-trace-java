This is a test-only project. There is no new instrumentation here.

Mongo has several client libraries and they can not be selectively used in one project.

This project is running tests using the `mongodb-driver` and `mongodb-driver-core`, version 3.7-3.12. Mongo 3.12 is the last version where `mongodb-driver` is available. For Mongo 4+ `mongodb-driver` is not used any more and therefore these versions are covered by `driver-4.0-test` project instead.
