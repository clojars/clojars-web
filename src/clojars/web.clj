(ns clojars.web
  (:require [clojars.db :as db]
            [clojars.config :refer [config]]
            [clojars.auth :refer [try-account]]
            [clojars.friend.registration :as registration]
            [clojars.web.dashboard :refer [dashboard index-page]]
            [clojars.web.error-page :refer [wrap-exceptions]]
            [clojars.web.search :refer [search]]
            [clojars.web.browse :refer [browse]]
            [clojars.web.common :refer [html-doc]]
            [clojars.web.safe-hiccup :refer [raw]]
            [clojure.java.io :as io]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.flash :refer [wrap-flash]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [compojure.core :refer [defroutes GET POST PUT ANY context routes]]
            [compojure.route :refer [not-found]]
            [cemerick.friend :as friend]
            [cemerick.friend.credentials :as creds]
            [cemerick.friend.workflows :as workflows]
            [clojars.routes.session :as session]
            [clojars.routes.user :as user]
            [clojars.routes.artifact :as artifact]
            [clojars.routes.group :as group]
            [clojars.routes.repo :as repo]
            [clojars.routes.api :as api]))

(defroutes main-routes
  (GET "/" _
       (try-account
        (if account
          (dashboard account)
          (index-page account))))
  (GET "/search" {:keys [params]}
       (try-account
        (search account params)))
  (GET "/projects" {:keys [params]}
       (try-account
        (browse account params)))
  (GET "/security" []
       (try-account
        (html-doc account "Security"
                  (raw (slurp (io/resource "security.html"))))))
  session/routes
  group/routes
  artifact/routes
  ;; user routes must go after artifact routes
  ;; since they both catch /:identifier
  user/routes
  api/routes
  (GET "/error" _ (throw (Exception. "What!? You really want an error?")))
  (PUT "*" _ {:status 405 :headers {} :body "Did you mean to use /repo?"})
  (ANY "*" _
       (try-account
        (not-found
         (html-doc account
                   "Page not found"
                   [:div.small-section
                    [:h1 "Page not found"]
                    [:p "Thundering typhoons!  I think we lost it.  Sorry!"]])))))

(defn bad-attempt [attempts user]
  (let [failures (or (attempts user) 0)]
    (Thread/sleep (* failures failures)))
  (update-in attempts [user] (fnil inc 0)))

(def credential-fn
  (let [attempts (atom {})]
    (partial creds/bcrypt-credential-fn
             (fn [id]
               (if-let [{:keys [user password]}
                        (db/find-user-by-user-or-email id)]
                 (when-not (empty? password)
                   (swap! attempts dissoc user)
                   {:username user :password password})
                 (do (swap! attempts bad-attempt id) nil))))))

(defn wrap-x-frame-options [f]
  (fn [req] (update-in (f req) [:headers] assoc "X-Frame-Options" "DENY")))

(defn https-request? [req]
  (or (= (:scheme req) :https)
      (= (get-in req [:headers "x-forwarded-proto"]) "https")))

(defn wrap-secure-session [f]
  (let [secure-session (wrap-session f {:cookie-attrs {:secure true
                                                      :http-only true}})
        regular-session (wrap-session f {:cookie-attrs {:http-only true}})]
    (fn [req]
      (if (https-request? req)
        (secure-session req)
        (regular-session req)))))

(defroutes clojars-app
  (context "/repo" _
           (-> repo/routes
               (friend/authenticate
                {:credential-fn credential-fn
                 :workflows [(workflows/http-basic :realm "clojars")]
                 :allow-anon? false
                 :unauthenticated-handler
                 (partial workflows/http-basic-deny "clojars")})
               (repo/wrap-file (:repo config))))
  (-> main-routes
      (friend/authenticate
       {:credential-fn credential-fn
        :workflows [(workflows/interactive-form)
                    registration/workflow]})
      (wrap-anti-forgery)
      (wrap-exceptions)
      (wrap-x-frame-options)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-multipart-params)
      (wrap-flash)
      (wrap-secure-session)
      (wrap-resource "public")
      (wrap-file-info)))
