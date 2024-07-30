(ns clojars.friend.oauth
  (:require
   [cemerick.friend.workflows :as workflow]
   [clojars.db :as db]
   [clojars.log :as log]
   [clojars.oauth.service :as oauth-service]
   [ring.util.response :refer [redirect]]))

(defn- handle-error [{:keys [params] ::keys [oauth-service]}]
  (let [{:keys [error error_description]} params]
    (when error
      (if (= "access_denied" error)
        (assoc (redirect "/login")
               :flash (format "You declined access to your %s account"
                              (oauth-service/provider-name oauth-service)))
        (throw (ex-info error_description {:error error}))))))

(defn- get-emails+login [{:keys [params] ::keys [http-service oauth-service]}]
  (let [code (:code params)
        token (oauth-service/access-token oauth-service code)
        {:keys [emails login]} (oauth-service/get-user-details
                                oauth-service http-service token)
        provider-name (oauth-service/provider-name oauth-service)]
    (if (seq emails)
      {::emails emails
       ::login login
       ::token token
       ::provider provider-name}
      (assoc (redirect "/login")
             :flash (format "No verified emails were found in your %s account"
                            provider-name)))))

(defn- find-user [{::keys [db emails oauth-service]}]
  (if-let [user (db/find-user-by-email-in db emails)]
    {::user user}
    (let [provider-name (oauth-service/provider-name oauth-service)
          message (format "No account emails match the verified emails we got from %s"
                          provider-name)
          message (if (= "GitLab" provider-name)
                    (str message ". Note: your Clojars email must be your primary email in GitLab, since the GitLab API does't provide a way to get verified secondary emails.")
                    message)]
      (assoc (redirect "/register")
             :flash message))))

(defn- make-auth [{::keys [login provider user]}]
  (when-some [username (:user user)]
    {:identity username
     :username username
     :auth-provider provider
     :provider-login login}))

(defn authorize [service]
  (redirect (oauth-service/authorization-url service)))

(defn callback [req oauth-service http-service db]
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
    (doseq [result (db/maybe-verify-provider-groups db res)]
      (log/info result))
    res))
