--name: find-user
SELECT *
FROM users
WHERE "user" = :username
LIMIT 1;

--name: find-user-by-id
SELECT *
FROM users
WHERE id = :id
LIMIT 1;

--name: find-user-by-user-or-email
SELECT *
FROM users
WHERE (
  "user" = :username_or_email
  OR
  email = :username_or_email
)
LIMIT 1;

--name: find-user-by-email-in
SELECT *
FROM users
WHERE (
  email IN (:email)
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

--name: find-user-tokens-by-username
SELECT *
FROM deploy_tokens
WHERE (
  user_id = (SELECT id
             FROM users
             WHERE "user" = :username
             LIMIT 1)
);

--name: find-token
select *
FROM deploy_tokens
WHERE id = :id;

--name: find-tokens-by-hash
select *
FROM deploy_tokens
WHERE token_hash = :token_hash;

--name: find-groupnames
SELECT name
FROM groups
WHERE (
      "user" = :username
      AND
      inactive IS NOT true
);

--name: group-membernames
SELECT "user"
FROM groups
WHERE (
      name = :groupname
      AND
      inactive IS NOT true
      AND
      admin IS NOT true
);

--name: group-activenames
SELECT "user"
FROM groups
WHERE (
      name = :groupname
      AND
      inactive IS NOT true
);

--name: group-allnames
SELECT "user"
FROM groups
WHERE (
      name = :groupname
);


--name: group-actives
SELECT "user", admin
FROM groups
WHERE (
      name = :groupname
      AND
      inactive IS NOT true
);

--name: group-adminnames
SELECT "user"
FROM groups
WHERE (
      name = :groupname
      AND
      inactive IS NOT true
      AND
      admin = true
);

--name: inactivate-member!
UPDATE groups
SET inactive = true, inactivated_by = :inactivated_by
WHERE (
      "user" = :username
      AND
      name = :groupname
      AND
      inactive IS NOT true
);

--name: jars-by-username
SELECT j.*
FROM jars j
JOIN (
  SELECT group_name, jar_name, MAX(created) AS created
  FROM jars
  WHERE "user" = :username
  GROUP BY group_name, jar_name
) l
ON (
   j.group_name = l.group_name
   AND
   j.jar_name = l.jar_name
   AND
   j.created = l.created
)
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: jars-by-groupname
SELECT j.*
FROM jars j
JOIN (
  SELECT  jar_name, MAX(created) AS created
  FROM jars
  WHERE group_name = :groupname
  GROUP BY group_name, jar_name
) l
ON (
   j.jar_name = l.jar_name
   AND
   j.created = l.created
)
ORDER BY j.group_name ASC, j.jar_name ASC;

--name: recent-versions
SELECT version
FROM 
(SELECT DISTINCT ON (version) version, created FROM jars
 WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
 )) AS distinct_jars
ORDER BY distinct_jars.created DESC;

--name: recent-versions-limit
SELECT version
FROM 
(SELECT DISTINCT ON (version) version, created FROM jars
 WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
 )) AS distinct_jars
ORDER BY distinct_jars.created DESC
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
  ORDER BY created DESC
  LIMIT 6
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

--name: all-jars
SELECT * FROM jars;

--name: jar-exists
SELECT EXISTS(
  SELECT 1
  FROM jars
  WHERE (
    group_name = :groupname
    AND
    jar_name = :jarname
  )
) as exist;

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

--name: max-jars-id
SELECT max(id) AS max_id FROM jars;

--name: find-dependencies
SELECT *
FROM deps
WHERE (
  group_name = :groupname
  AND
  jar_name = :jarname
  AND
  version = :version
);

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
) AS sub;

--name: count-projects-before
SELECT COUNT(*) AS count
FROM (
  SELECT DISTINCT group_name, jar_name
  FROM jars
  ORDER BY group_name, jar_name
) AS sub
WHERE group_name || '/' || jar_name < :s;

--name: insert-user!
INSERT INTO users (email, "user", password, created)
VALUES (:email, :username, :password,:created);

--name: update-user!
UPDATE users
SET email = :email, "user" = :username, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE "user" = :account;

--name: update-user-with-password!
UPDATE users
SET email = :email, "user" = :username, password = :password, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE "user" = :account;

--name: reset-user-password!
UPDATE users
SET password = :password, password_reset_code = NULL, password_reset_code_created_at = NULL
WHERE (
  password_reset_code = :reset_code
  AND
  "user" = :username
);

--name: set-password-reset-code!
UPDATE users
SET password_reset_code = :reset_code, password_reset_code_created_at = :reset_code_created_at
WHERE "user" = :username;

--name: set-otp-secret-key!
UPDATE users
SET otp_secret_key = :otp_secret_key
WHERE "user" = :username;

--name: enable-otp!
UPDATE users
SET otp_recovery_code = :otp_recovery_code, otp_active = true
WHERE "user" = :username;

--name: disable-otp!
UPDATE users
SET otp_secret_key = null, otp_recovery_code = null, otp_active = false
WHERE "user" = :username;

--name: insert-deploy-token!
INSERT INTO deploy_tokens (name, user_id, token, token_hash, group_name, jar_name)
VALUES (:name, :user_id, :token, :token_hash, :group_name, :jar_name);

--name: disable-deploy-token!
UPDATE deploy_tokens
SET disabled = true, updated = :updated
WHERE id = :token_id

--name: set-deploy-token-used!
UPDATE deploy_tokens
SET last_used = :timestamp
WHERE id = :token_id

--name: set-deploy-token-hash!
UPDATE deploy_tokens
SET token_hash = :token_hash
WHERE id = :token_id AND token_hash IS NULL

--name: find-groups-jars-information
SELECT j.jar_name, j.group_name, homepage, description, "user",
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
SELECT j.jar_name, j.group_name, homepage, description, "user",
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
INSERT INTO groups (name, "user", added_by, admin)
VALUES (:groupname, :username, :added_by, :admin);

--name: add-jar!
INSERT INTO jars (group_name, jar_name, version, "user", created, description, homepage, authors, packaging, licenses, scm)
VALUES (:groupname, :jarname, :version, :user, :created, :description, :homepage, :authors, :packaging, :licenses, :scm);

--name: add-dependency!
INSERT INTO deps (group_name, jar_name, version, dep_group_name, dep_jar_name, dep_version, dep_scope)
VALUES (:groupname, :jarname, :version, :dep_groupname, :dep_jarname, :dep_version, :dep_scope);

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

--name: delete-groups-dependencies!
DELETE FROM deps
WHERE (
group_name = :group_id
)

--name: delete-dependencies!
DELETE FROM deps
WHERE (
group_name = :group_id
AND
jar_name = :jar_id
)

--name: delete-dependencies-version!
DELETE FROM deps
WHERE (
group_name = :group_id
AND
jar_name = :jar_id
AND
version = :version
)

--name: delete-group!
DELETE FROM groups
WHERE name = :group_id;

--name: clear-groups!
DELETE FROM groups;

--name: clear-jars!
DELETE FROM jars;

--name: clear-users!
DELETE FROM users;
