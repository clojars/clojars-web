create table users
       (id serial not null PRIMARY KEY,
        "user" TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
        salt TEXT NOT NULL,
        email TEXT NOT NULL,
        ssh_key TEXT NOT NULL,
        pgp_key TEXT,
        password_reset_code TEXT,
        password_reset_code_created_at TIMESTAMP,
        created TIMESTAMP NOT NULL);

create table jars
       (id serial not null PRIMARY KEY,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        "user" TEXT NOT NULL,
        created TIMESTAMP NOT NULL,
        promoted_at TIMESTAMP,
        description TEXT,
        homepage TEXT,
        scm TEXT,
        authors TEXT,
        licenses TEXT,
        packaging TEXT);

create index jars_idx0 on jars
       (group_name, jar_name, created DESC);
                        
create table deps
       (id serial not null PRIMARY KEY,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        dep_group_name TEXT NOT NULL,
        dep_jar_name TEXT NOT NULL,
        dep_version TEXT NOT NULL,
        dep_scope TEXT);

create table groups
       (id serial not null PRIMARY KEY,
        name TEXT NOT NULL,
        "user" TEXT NOT NULL,
        added_by TEXT,
        admin BOOLEAN,
        inactive BOOLEAN,
        inactivated_by TEXT);
