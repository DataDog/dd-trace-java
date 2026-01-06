This is a test-only project. There is no new instrumentation here.

Mongo has several client libraries and they can not be selectively used in one project.

This project is running tests using the `mongodb-driver-sync`, version 3.10-3.12. Mongo 4.0 is changing the behaviour and becasue of that the versions after 4.0 need to be tested in a separate project `driver-4.0-test`.
