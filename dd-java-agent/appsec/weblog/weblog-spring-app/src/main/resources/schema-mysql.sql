DROP TABLE IF EXISTS thetable;
CREATE TABLE thetable(
    id serial,
    val varchar(512) not null,
    primary key(id)
);

-- Could be created by hibernate, but we insert data afterwards
DROP TABLE IF EXISTS posts;
CREATE TABLE posts(
     id serial,
     author varchar(256) not null,
     title varchar(512) not null,
     body varchar(4096) not null,
     created_at datetime not null,
     primary key(id)
);
--
INSERT INTO posts(id, author, title, body, created_at) VALUES (1, 'Sample author', 'sample title', 'my sample body', now());

INSERT INTO thetable(id, val) VALUES(1, 'One value');
INSERT INTO thetable(id, val) VALUES(2, 'Another value');
