# Build

```shell
# build webapp
gradle build
```

# Database support

- MongoDB (in-memory, embedded)
- hsqldb, *default* (in-memory)
- mysql `-Dspring.profiles.active=mysql`
- postgresql `-Dspring.profiles.active=postgresql`
- oracle `-Dspring.profiles.active=oracle`

## Local Mysql server

```shell
docker run -d \
-e MYSQL_DATABASE=sqreen-app \
-e MYSQL_USER=mysql \
-e MYSQL_PASSWORD=mysql \
-p 3306:3306 \
mysql/mysql-server:5.7
```

## Local PostgreSQL server

```shell
docker run -d \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_DB=sqreen-app \
  -p 5432:5432 \
  postgres:latest
```

## Local Oracle server

TODO: this image is not available anymore, we need to find an alternative

```shell
docker run -d \
  --shm-size=1g \
  -p 1521:1521 \
  -p 8081:8080 \
  -e ORACLE_PWD=password \
  -v /tmp/oracle:/u01/app/oracle/oradata \
  oracle/database:11.2.0.2-xe
```


