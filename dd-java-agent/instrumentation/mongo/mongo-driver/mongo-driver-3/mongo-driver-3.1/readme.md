This is an instrumentation project.

This project can be considered the 'base' implementation. In Mongo 3.4 the BsonWriter interface changed in binary incompatible way and therefore a separate implementation is needed, provided by a separate project `driver-3.4`.

Unfortunately, the entry points in Mongo 3.4 and later are overlapping with the pre-3.4 versions so we need a clever way of adding the `MongoCommandListener` instance which will follow user specified priority, making sure that just the 'latest' implementation will be hooked in.

The bundled client test is using `mongo-driver-java` which is supported till Mongo 3.12 - other versions and client libs are tested in separate test projects.
