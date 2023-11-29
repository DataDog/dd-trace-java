create table tutorials
(
    id          bigserial primary key,
    title       varchar(255),
    description varchar(255),
    published   boolean
);

INSERT INTO public.tutorials (id, title, description, published) VALUES (1, 'Spring Boot JDBC Template', 'Tut#3 Description', false);
INSERT INTO public.tutorials (id, title, description, published) VALUES (2, 'TypeScript Tutorial for Beginners', 'Tut#3 Description', false);
INSERT INTO public.tutorials (id, title, description, published) VALUES (3, 'JavaScript Tutorial', 'Tut#3 Description', false);
INSERT INTO public.tutorials (id, title, description, published) VALUES (4, 'C++ for Beginners', 'Tut#3 Description', false);
INSERT INTO public.tutorials (id, title, description, published) VALUES (5, 'Java tutorial', 'Tut#3 Description', false);
