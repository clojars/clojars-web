--name: find-user
SELECT *
FROM users
WHERE user = :username
LIMIT 1;

--name: find-user-by-user-or-email
SELECT *
FROM users
WHERE (
  user = :username_or_email
  OR
  email = :username_or_email
)
LIMIT 1;

--name: find-user-by-password-reset-code
SELECT *
FROM users
WHERE (
      password_reset_code = :reset_code
      AND
      password_reset_code_created_at >= :reset_code_created_at
)
LIMIT 1;

--name: find-groupnames
SELECT name
FROM groups
WHERE user = :username;

--name: group-membernames
SELECT user
FROM groups
WHERE name = :groupname;

--name: group-keys
SELECT users.pgp_key
FROM groups
INNER JOIN users
ON users.user = groups.user
WHERE groups.name = :groupname;

--name: jars-by-username
SELECT j.*
FROM jars j
JOIN (
  SELECT group_name, jar_name, MAX(created) AS created
  FROM jars
  GROUP BY group_name, jar_name
) l
ON (
   j.group_name = l.group_name
   AND
   j.jar_name = l.jar_name
   AND
   j.created = l.created
)
WHERE j.user = :username
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: jars-by-groupname
SELECT j.*
FROM jars j
JOIN (
  SELECT  jar_name, MAX(created) AS created
  FROM jars
  GROUP BY group_name, jar_name
) l
ON (
   j.jar_name = l.jar_name
   AND
   j.created = l.created
)
WHERE j.group_name = :groupname
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: recent-versions
SELECT DISTINCT(version)
FROM jars
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
)
ORDER BY created DESC;

--name: recent-versions-limit
SELECT DISTINCT(version)
FROM jars
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
)
ORDER BY created DESC
LIMIT :num;

--name: count-versions
SELECT COUNT(DISTINCT version) AS count
FROM jars
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
);

--name: recent-jars
SELECT j.*
FROM jars j
JOIN (
  SELECT group_name, jar_name, MAX(created) AS created
  FROM jars
  GROUP BY group_name, jar_name
) l
ON (
  j.group_name = l.group_name
  AND
  j.jar_name = l.jar_name
  AND
  j.created = l.created
)
ORDER BY l.created DESC
LIMIT 6;

--name: jar-exists
SELECT EXISTS(
  SELECT 1
  FROM jars
  WHERE (
    group_name = :groupname
    AND
    jar_name = :jarname
  )
);

--name: find-jar
SELECT *
FROM jars
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
)
ORDER BY version LIKE '%-SNAPSHOT' ASC, created DESC
LIMIT 1;

--name: find-jar-versioned
SELECT *
FROM jars
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
  AND
  version = :version
)
ORDER BY created DESC
LIMIT 1;

--name: all-projects
SELECT DISTINCT group_name, jar_name
FROM jars
ORDER BY group_name ASC, jar_name ASC
LIMIT :num
OFFSET :offset;

--name: count-all-projects
SELECT COUNT(*) AS count
FROM (
  SELECT DISTINCT group_name, jar_name
  FROM jars
);

--name: count-projects-before
SELECT COUNT(*) AS count
FROM (
  SELECT DISTINCT group_name, jar_name
  FROM jars
  ORDER BY group_name, jar_name
)
WHERE group_name || '/' || jar_name < :s;

--name: insert-user!
INSERT INTO 'users' (email, user, password, pgp_key, created, ssh_key, salt)
VALUES (:email, :username, :password, :pgp_key, :created, '', '');

--name: insert-group!
INSERT INTO 'groups' (name, user)
VALUES (:groupname, :username);

--name: update-user!
UPDATE users
SET email = :email, user = :username, pgp_key = :pgp_key, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE user = :account;

--name: update-user-with-password!
UPDATE users
SET email = :email, user = :username, pgp_key = :pgp_key, password = :password, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE user = :account;

--name: reset-user-password!
UPDATE users
SET password = :password, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE (
  password_reset_code = :reset_code
  AND
  user = :username
);

--name: set-password-reset-code!
UPDATE users
SET password_reset_code = :reset_code, password_reset_code_created_at = :reset_code_created_at
WHERE user = :username;

--name: find-groups-jars-information
SELECT j.jar_name, j.group_name, homepage, description, user,
j.version AS latest_version, r2.version AS latest_release
FROM jars j
-- Find the latest version
JOIN
(SELECT jar_name, group_name, MAX(created) AS created
FROM jars
WHERE group_name = :group_id
GROUP BY group_name, jar_name) l
ON j.jar_name = l.jar_name
AND j.group_name = l.group_name
-- Find basic info for latest version
AND j.created = l.created
-- Find the created ts for latest release
LEFT JOIN
(SELECT jar_name, group_name, MAX(created) AS created
FROM jars
WHERE version NOT LIKE '%-SNAPSHOT'
AND group_name = :group_id
GROUP BY group_name, jar_name) r
ON j.jar_name = r.jar_name
AND j.group_name = r.group_name
-- Find version for latest release
LEFT JOIN
(SELECT jar_name, group_name, version, created FROM jars
WHERE group_name = :group_id
) AS r2
ON j.jar_name = r2.jar_name
AND j.group_name = r2.group_name
AND r.created = r2.created
WHERE j.group_name = :group_id
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: find-jars-information
SELECT j.jar_name, j.group_name, homepage, description, user,
j.version AS latest_version, r2.version AS latest_release
FROM jars j
-- Find the latest version
JOIN
(SELECT jar_name, group_name, MAX(created) AS created
FROM jars
WHERE group_name = :group_id
AND jar_name = :artifact_id
GROUP BY group_name, jar_name) l
ON j.jar_name = l.jar_name
AND j.group_name = l.group_name
-- Find basic info for latest version
AND j.created = l.created
-- Find the created ts for latest release
LEFT JOIN
(SELECT jar_name, group_name, MAX(created) AS created
FROM jars
WHERE version NOT LIKE '%-SNAPSHOT'
AND group_name = :group_id
AND jar_name = :artifact_id
GROUP BY group_name, jar_name) r
ON j.jar_name = r.jar_name
AND j.group_name = r.group_name
-- Find version for latest release
LEFT JOIN
(SELECT jar_name, group_name, version, created FROM jars
WHERE group_name = :group_id
AND jar_name = :artifact_id
) AS r2
ON j.jar_name = r2.jar_name
AND j.group_name = r2.group_name
AND r.created = r2.created
WHERE j.group_name = :group_id
AND j.jar_name = :artifact_id
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: add-member!
INSERT INTO groups (name, user, added_by)
VALUES (:groupname, :username, :added_by);

--name: add-jar!
INSERT INTO jars (group_name, jar_name, version, user, created, description, homepage, authors)
VALUES (:groupname, :jarname, :version, :user, :created, :description, :homepage, :authors);

--name: promote!
UPDATE jars
SET promoted_at = :promoted_at
WHERE (
  group_name = :group_id
  AND
  jar_name = :artifact_id
  AND
  version = :version
);

--name: promoted
SELECT promoted_at
FROM jars
WHERE (
  group_name = :group_id
  AND
  jar_name = :artifact_id
  AND
  version = :version
);

--name: delete-groups-jars!
DELETE FROM jars
WHERE group_name = :group_id;

--name: delete-jars!
DELETE FROM jars
WHERE (
  group_name = :group_id
  AND
  jar_name = :jar_id
);

--name: delete-jar-version!
DELETE FROM jars
WHERE (
  group_name = :group_id
  AND
  jar_name = :jar_id
  AND
  version = :version
);

--name: delete-group!
DELETE FROM groups
WHERE name = :group_id;

--name: clear-groups!
DELETE FROM groups;

--name: clear-jars!
DELETE FROM jars;

--name: clear-users!
DELETE FROM users;
