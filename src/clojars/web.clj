(ns clojars.web
  (:require
   [cemerick.friend :as friend]
   [cemerick.friend
    [workflows :as workflows]]
   [clojars
    [auth :as auth :refer [try-account]]
    [config :refer [config]]
    [errors :refer [wrap-exceptions]]
    [http-utils :refer [wrap-x-frame-options wrap-secure-session]]
    [middleware :refer [wrap-ignore-trailing-slash]]]
   [clojars.friend.registration :as registration]
   [clojars.friend.github :as github]
   [clojars.log :as log]
   [clojars.routes.api :as api]
   [clojars.routes.artifact :as artifact]
   [clojars.routes.group :as group]
   [clojars.routes.repo :as repo]
   [clojars.routes.session :as session]
   [clojars.routes.token :as token]
   [clojars.routes.token-breach :as token-breach]
   [clojars.routes.user :as user]
   [clojars.web
    [browse :refer [browse]]
    [common :refer [html-doc]]
    [dashboard :refer [dashboard index-page]]
    [safe-hiccup :refer [raw]]
    [search :refer [search]]]
   [clojure.java.io :as io]
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
    [resource :refer [wrap-resource]]]))

(defn try-parse-page
  "Will throw a targeted error if maybe-page doesn't parse as an integer."
  [maybe-page]
  (try
    (Integer/parseInt maybe-page)
    (catch NumberFormatException _nfe
      (throw (ex-info
                "page must be an integer"
                {:report? false
                 :title "Bad Request"
                 :error-message "The page query parameter must be an integer."
                 :status 400})))))

(defn- main-routes [db stats search-obj mailer]
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
   (GET "/dmca" []
        (try-account
          #(html-doc "DMCA" {:account %}
             (raw (slurp (io/resource "dmca.html"))))))
   session/routes
   (group/routes db)
   (artifact/routes db stats)
   ;; user routes must go after artifact routes
   ;; since they both catch /:identifier
   (user/routes db mailer)
   (token/routes db)
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

(defn clojars-app [storage db reporter stats search mailer github]
  (routes
    (-> (context "/repo" _
          (-> (repo/routes storage db search)
              (friend/authenticate
               {:credential-fn (auth/token-credential-fn db)
                :workflows [(workflows/http-basic :realm "clojars")]
                :allow-anon? false
                :unauthenticated-handler
                (partial workflows/http-basic-deny "clojars")})
              (repo/wrap-reject-non-token)
              (repo/wrap-exceptions reporter)
              (repo/wrap-file (:repo (config)))
              (log/wrap-request-context)
              (repo/wrap-reject-double-dot)))
        (wrap-secure-session))
    (-> (token-breach/routes db)
        (wrap-exceptions reporter)
        (log/wrap-request-context))
   (-> (main-routes db stats search mailer)
       (friend/authenticate
        {:credential-fn (auth/password-credential-fn db)
         :workflows [(auth/interactive-form-with-mfa-workflow)
                     (registration/workflow db)
                     (github/workflow github db)]})
       (wrap-exceptions reporter)
       (log/wrap-request-context)
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

(defn handler-optioned [{:keys [storage db error-reporter stats search mailer github]}]
  (clojars-app storage (:spec db) error-reporter stats search mailer github))
