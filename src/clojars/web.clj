(ns clojars.web
  (:require [cemerick.friend :as friend]
            [cemerick.friend
             [credentials :as creds]
             [workflows :as workflows]]
            [clojars
             [auth :refer [try-account]]
             [config :refer [config]]
             [db :as db]
             [errors :refer [wrap-exceptions]]
             [http-utils :refer [wrap-x-frame-options wrap-secure-session]]
             [middleware :refer [wrap-ignore-trailing-slash]]]
            [clojars.friend.registration :as registration]
            [clojars.routes
             [api :as api]
             [artifact :as artifact]
             [group :as group]
             [repo :as repo]
             [session :as session]
             [user :as user]]
            [clojars.web
             [browse :refer [browse]]
             [common :refer [html-doc]]
             [dashboard :refer [dashboard index-page]]
             [safe-hiccup :refer [raw]]
             [search :refer [search]]]
            [clojure.java.io :as io]
            [clojure.set :refer [rename-keys]]
            [compojure
             [core :refer [ANY context GET PUT routes]]
             [route :refer [not-found]]]
            [ring.middleware
             [anti-forgery :refer [wrap-anti-forgery]]
             [content-type :refer [wrap-content-type]]
             [flash :refer [wrap-flash]]
             [keyword-params :refer [wrap-keyword-params]]
             [multipart-params :refer [wrap-multipart-params]]
             [not-modified :refer [wrap-not-modified]]
             [params :refer [wrap-params]]
             [resource :refer [wrap-resource]]
             [session :refer [wrap-session]]]))

(defn try-parse-page
  "Will throw a targeted error if maybe-page doesn't parse as an integer."
  [maybe-page]
  (try
    (Integer/parseInt maybe-page)
    (catch NumberFormatException nfe
      (throw (ex-info
                "page must be an integer"
                {:report? false
                 :title "Bad Request"
                 :error-message "The page query parameter must be an integer."
                 :status 400})))))

(defn main-routes [db reporter stats search-obj mailer]
  (routes
   (GET "/" _
     (try-account
       #(if %
          (dashboard db %)
          (index-page db stats %))))
   (GET "/search" {:keys [params]}
        (try-account
          #(let [validated-params (if (:page params)
                                    (assoc params :page (try-parse-page (:page params)))
                                    params)]
             (search search-obj % validated-params))))
   (GET "/projects" {:keys [params]}
         (try-account
           #(let [validated-params (if (:page params)
                                     (assoc params :page (try-parse-page (:page params)))
                                     params)]
             (browse db % validated-params))))
   (GET "/security" []
        (try-account
          #(html-doc "Security" {:account %}
             (raw (slurp (io/resource "security.html"))))))
   session/routes
   (group/routes db)
   (artifact/routes db reporter stats)
   ;; user routes must go after artifact routes
   ;; since they both catch /:identifier
   (user/routes db mailer)
   (api/routes db stats)
   (GET "/error" _ (throw (Exception. "What!? You really want an error?")))
   (PUT "*" _ {:status 405 :headers {} :body "Did you mean to use /repo?"})
   (ANY "*" _
        (try-account
         #(not-found
            (html-doc "Page not found" {:account %}
              [:div.small-section
               [:h1 "Page not found"]
               [:p "Thundering typhoons!  I think we lost it.  Sorry!"]]))))))

(defn bad-attempt [attempts user]
  (let [failures (or (attempts user) 0)]
    (Thread/sleep (* failures failures)))
  (update-in attempts [user] (fnil inc 0)))

(defn user-credentials [db username]
  (when-let [user (db/find-user db username)]
    (when-not (empty? (:password user))
      (rename-keys user {:user :username}))))

(defn credential-fn [db]
  (let [attempts (atom {})]
    (fn [{:keys [username] :as auth-map}]
      (if-let [auth-result (creds/bcrypt-credential-fn
                             (partial user-credentials db)
                             auth-map)]
        (do
          (swap! attempts dissoc username)
          auth-result)
        (do
          (swap! attempts bad-attempt username)
          nil)))))

(defn clojars-app [storage db reporter stats search mailer]
  (routes
    (-> (context "/repo" _
          (-> (repo/routes storage db reporter search)
            (friend/authenticate
              {:credential-fn (credential-fn db)
               :workflows [(workflows/http-basic :realm "clojars")]
               :allow-anon? false
               :unauthenticated-handler
               (partial workflows/http-basic-deny "clojars")})
            (repo/wrap-exceptions reporter)
            (repo/wrap-file (:repo config))
            (repo/wrap-reject-double-dot)))
      (wrap-secure-session))
   (-> (main-routes db reporter stats search mailer)
       (friend/authenticate
        {:credential-fn (credential-fn db)
         :workflows [(workflows/interactive-form)
                     (registration/workflow db)]})
       (wrap-exceptions reporter)
       (wrap-anti-forgery)
       (wrap-x-frame-options)
       (wrap-keyword-params)
       (wrap-params)
       (wrap-multipart-params)
       (wrap-flash)
       (wrap-secure-session)
       (wrap-resource "public")
       (wrap-content-type)
       (wrap-not-modified)
       (wrap-ignore-trailing-slash))))

(defn handler-optioned [{:keys [storage db error-reporter stats search mailer]}]
  (clojars-app storage (:spec db) error-reporter stats search mailer))
