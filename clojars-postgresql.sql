create table users
       (id serial not null PRIMARY KEY,
        "user" TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
        salt TEXT NOT NULL,
        email TEXT NOT NULL,
        ssh_key TEXT NOT NULL,
        created DATE NOT NULL);

create table jars
       (id serial not null PRIMARY KEY,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        "user" TEXT NOT NULL,
        created DATE NOT NULL,
        description TEXT,
        homepage TEXT,
        scm TEXT,
        authors TEXT);

create table deps
       (id serial not null PRIMARY KEY,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        dep_group_name TEXT NOT NULL,
        dep_jar_name TEXT NOT NULL,
        dep_version TEXT NOT NULL);

create table groups
       (id serial not null PRIMARY KEY,
        name TEXT NOT NULL,
        "user" TEXT NOT NULL);
