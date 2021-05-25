DROP TABLE IF EXISTS thetable;
CREATE TABLE thetable(
    id int identity primary key,
    val varchar(512) not null
);

-- Could be created by hibernate, but we insert data afterwards
DROP TABLE IF EXISTS posts;
CREATE TABLE posts(
    id int identity primary key,
    author varchar(256) not null,
    title varchar(512) not null,
    body longvarchar not null,
    created_at time with time zone not null
);

INSERT INTO posts(id, author, title, body, created_at) VALUES(1, 'Sample author', 'sample title', 'my sample body', current_time);

INSERT INTO thetable(id, val) VALUES(1, 'One value');
INSERT INTO thetable(id, val) VALUES(2, 'Another value');
