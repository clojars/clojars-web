(ns clojars.friend.github
  (:require [ring.util.response :refer [redirect]]
            [cemerick.friend.workflows :as workflow]
            [clojars.github :as github]
            [clojars.db :as db]))

(defn- authorize [service]
  (redirect (github/authorization-url service)))

(defn- handle-error [{:keys [params]}]
  (let [{:keys [error error_description]} params]
    (when error
      (if (= error "access_denied")
        (assoc (redirect "/login")
               :flash "You declined access to your account")
        (throw (ex-info error_description {:error error}))))))

(defn- get-emails [{:keys [params] ::keys [service]}]
  (let [code (:code params)
        token (github/access-token service code)
        emails (github/get-verified-emails service token)]
    (if (seq emails)
      {::emails emails
       ::token token}
      (assoc (redirect "/login")
             :flash "No verified e-mail was found"))))

(defn- get-github-login [{::keys [service token]}]
  {::login (github/get-login service token)})

(defn- find-user [{::keys [db emails]}]
  (if-let [user (db/find-user-by-email-in db emails)]
    {::user user}
    (assoc (redirect "/register")
           :flash "None of your e-mails are registered")))

(defn- make-auth [{::keys [login user]}]
  (when-some [username (:user user)]
    {:identity username
     :username username
     :auth-provider :github
     :provider-username login}))

(defn- callback [req service db]
  (let [res
        (reduce (fn [acc f]
                  (let [res (merge acc (f acc))]
                    (cond
                      (:status res)   (reduced res)
                      (:identity res) (workflow/make-auth res)
                      :else           res)))

                (assoc req
                       ::db db
                       ::service service)

                [handle-error
                 get-emails
                 find-user
                 get-github-login
                 make-auth])]
    (db/maybe-verify-provider-groups db res)
    res))

(defn workflow [service db]
  (fn [req]
    (case (:uri req)
      "/oauth/github/authorize" (authorize service)
      "/oauth/github/callback" (callback req service db)
      nil)))
