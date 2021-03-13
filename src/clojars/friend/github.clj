(ns clojars.friend.github
  (:require
   [cemerick.friend.workflows :as workflow]
   [clojars.db :as db]
   [clojars.oauth.service :as oauth-service]
   [ring.util.response :refer [redirect]]))

(defn- authorize [service]
  (redirect (oauth-service/authorization-url service)))

(defn- handle-error [{:keys [params]}]
  (let [{:keys [error error_description]} params]
    (when error
      (if (= error "access_denied")
        (assoc (redirect "/login")
               :flash "You declined access to your account")
        (throw (ex-info error_description {:error error}))))))

(defn- get-emails+login [{:keys [params] ::keys [http-service oauth-service]}]
  (let [code (:code params)
        token (oauth-service/access-token oauth-service code)
        {:keys [emails login]} (oauth-service/get-user-details
                                oauth-service http-service token)
        verified-emails (into []
                              (comp (filter :verified)
                                    (map :email))
                              emails)]
    (if (seq verified-emails)
      {::emails verified-emails
       ::login login
       ::token token
       ::provider (oauth-service/provider-name oauth-service)}
      (assoc (redirect "/login")
             :flash "No verified e-mail was found"))))

(defn- find-user [{::keys [db emails]}]
  (if-let [user (db/find-user-by-email-in db emails)]
    {::user user}
    (assoc (redirect "/register")
           :flash "None of your e-mails are registered")))

(defn- make-auth [{::keys [login provider user]}]
  (when-some [username (:user user)]
    {:identity username
     :username username
     :auth-provider provider
     :provider-login login}))

(defn- callback [req oauth-service http-service db]
  (let [res
        (reduce (fn [acc f]
                  (let [res (merge acc (f acc))]
                    (cond
                      (:status res)   (reduced res)
                      (:identity res) (workflow/make-auth res)
                      :else           res)))

                (assoc req
                       ::db db
                       ::http-service http-service
                       ::oauth-service oauth-service)

                [handle-error
                 get-emails+login
                 find-user
                 make-auth])]
    (db/maybe-verify-provider-groups db res)
    res))

(defn workflow [oauth-service http-service db]
  (fn [req]
    (case (:uri req)
      "/oauth/github/authorize" (authorize oauth-service)
      "/oauth/github/callback" (callback req oauth-service http-service db)
      nil)))
