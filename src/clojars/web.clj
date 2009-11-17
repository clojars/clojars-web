(ns clojars.web
  (:require [clojure.contrib.sql :as sql])
  (:use compojure clojars.db))

(defn when-ie [& contents]
  (str
   "<!--[if IE]>"
   (apply html contents)
   "<![endif]-->"))

(defn html-doc [account title & body]
  (html 
   "<!DOCTYPE html>"
   [:html {:lang :en}
    [:head
     [:meta {:charset "utf-8"}]
     [:title
      (when title
        (str title " | "))
      "Clojars"]
     (map #(include-css (str "/stylesheets/" %))
          ["reset.css" "grid.css" "screen.css"])
     (when-ie (include-js "/js/html5.js"))]

    [:body
     [:div {:class "container_12 header"}
      [:header
       [:hgroup {:class :grid_4}
        [:h1 (link-to "/" "Clojars")]
        [:h2 "Simple Clojure jar repository"]]
       [:nav
        (if account
          (unordered-list
           [(link-to "/" "dashboard")
            (link-to "/profile" "profile")
            (link-to "/logout" "logout")])
          (unordered-list
           [(link-to "/login" "login")
            (link-to "/register" "register")]))
        [:input {:value "No search yet" :class :search}]]]
      [:div {:class :clear}]]]
    [:div {:class "container_12 article"}
     [:article
      body]]
    [:footer
     (link-to "mailto:contact@clojars.org" "contact")
     (link-to "http://github.com/ato/clojars-web" "code")
     (link-to "http://wiki.github.com/ato/clojars-web" "help")]]))

(defn login-form [ & [error]]
  (html-doc nil "Login"
   [:h1 "Login"]
   [:p "Don't have an account? "
    (link-to "/register" "Sign up!")]
  
   (when error
     [:div {:class :error} (str error)])
   (form-to [:post "/login"]
     (label :user "Username or email:")
     (text-field :user)
     (label :password "Password:")
     (password-field :password)
     (submit-button "Login"))))

(defn login [{username :user password :password}]
  (if-let [user (auth-user username password)]
    [(session-assoc :account (:user user))
     (redirect-to "/")]
    (login-form "Incorrect username or password.")))

(defn error-list [errors]
  (when errors
     [:div {:class :error} 
      [:strong "Blistering barnacles!"]
      "  Something's not shipshape:"
      (unordered-list errors)]))

(defn register-form [ & [errors email user ssh-key]]
  (html-doc nil "Register"
   [:h1 "Register"]
   (error-list errors)
   (form-to [:post "/register"]
     (label :email "Email:")
     [:input {:type :email :name :email :id :email :value email}]
     (label :user "Username:")
     (text-field :user user)
     (label :password "Password:")
     (password-field :password)
     (label :confirm "Confirm password:")
     (password-field :confirm)
     (label :ssh-key "SSH public key:")
     " ("(link-to "http://wiki.github.com/ato/clojars-web/ssh-keys" "what's this?")")"
     (text-area :ssh-key ssh-key)
     (submit-button "Register"))))

(defn conj-when [coll test x]
  (if test
    (conj coll x)
    coll))


(defn valid-ssh-key? [key]
  (re-matches #"(ssh-\w+ \S+|\d+ \d+ \D+).*\s*" key))

(defn validate-profile 
  "Validates a profile, returning nil if it's okay, otherwise a list
  of errors."
  [account email user password confirm ssh-key]
  (-> nil
      (conj-when (blank? email) "Email can't be blank")
      (conj-when (blank? user) "Username can't be blank")
      (conj-when (blank? password) "Password can't be blank")
      (conj-when (not= password confirm) 
                 "Password and confirm password must match")
      (conj-when (or (*reserved-names* user)  ; "I told them we already
                     (and (not= account user) ; got one!" 
                          (find-user user))
                     (seq (group-members user))) 
                 "Username is already taken")
      (conj-when (not (re-matches #"[a-z0-9_-]+" user))
                 (str "Usernames must consist only of lowercase "
                      "letters, numbers, hyphens and underscores."))
      (conj-when (not (or (blank? ssh-key)
                          (valid-ssh-key? ssh-key)))
                 "Invalid SSH public key")))

(defn register [{email :email, user :user, password :password, 
                 confirm :confirm, ssh-key :ssh-key}]
  (if-let [errors (validate-profile nil email user password confirm ssh-key)]
    (register-form errors email user ssh-key)
    (do (add-user email user password ssh-key)
        [(set-session {:account user})
         (redirect-to "/")])))

(defn profile-form [account & [errors]]
  (let [user (find-user account)]
    (html-doc account "Profile"
     [:h1 "Profile"]
     (error-list errors)
     (form-to [:post "/profile"]
     (label :email "Email:")
     [:input {:type :email :name :email :id :email :value (user :email)}]
     (label :password "Password:")
     (password-field :password)
     (label :confirm "Confirm password:")
     (password-field :confirm)
     (label :ssh-key "SSH public key:")
     (text-area :ssh-key (user :ssh_key))
     (submit-button "Update")))))

(defn update-profile [account {email :email, password :password, 
                               confirm :confirm, ssh-key :ssh-key}]
  (if-let [errors (validate-profile account email account password confirm ssh-key)]
    (profile-form account errors)
    (do (update-user account email account password ssh-key)
        [(redirect-to "/profile")])))

(defn show-user [account user]
  (html-doc account (str user "'s jars")
    [:h1 (str user "'s jars")]
    (unordered-list 
     (for [jar (jars-by-user user)]
       (html (link-to (str "/" (:user jar) "/" (:jar_name jar)) 
                  (:jar_name jar))
             " "(:version jar))))))

(defn tag [s]
  (html [:span {:class "tag"} (h s)]))

(defn show-jar [account {:keys [user jarname]}]
  (let [jar (find-jar user jarname)]
   (html-doc account jarname
     [:h1 (link-to (str "/" user) (h user)) " / " (h jarname)]
     (:description jar)

     [:div {:class "useit"}
      [:h2 "USE IT"]
      [:h3 "with Leiningen"]
      [:div {:class "lein"} 
       [:span
        "["
        (h (:group_name jar)) "/" (h (:jar_name jar))
        " \"" (h (:version jar)) "\"]" ]]

      [:h3 "with Maven"]
      [:div {:class "maven"} 
       [:pre
        (tag "<dependency>\n")
        (tag "  <groupId>") (:group_name jar) (tag "</groupId>\n")
        (tag "  <artifactId>") (:jar_name jar) (tag "</artifactId>\n")
        (tag "  <version>") (:version jar) (tag "</version>\n")
        (tag "</dependency>")]]]
     )))

(defn index-page [account]
  (html-doc account nil
    "<h1>Coming soon!</h1>
        <p>Clojars.org will be a <strong>dead easy</strong> jar repository for open source 
          <a href=\"http://clojure.org/\">Clojure</a> libraries.</p>
        <h2>But what about Maven?</h2>

        <p>Maven is not exactly <strong>simple</strong> and it's a <a
        href=\"http://maven.apache.org/guides/mini/guide-central-repository-upload.html\">hassle</a>
        to get things into the central repository.  Maven central also
        isn't suited for forking or personal projects.  Clojars is
        intended to make distributing code as easy as possibe for
        <strong>everyone</strong>.</p> 
        <p>But <strong>don't worry</strong>, the plan is to expose
        things as a Maven and Ivy compatible repository so you can use
        them if you want to.</p>
        <h2>But I <em>hate</em> Maven!</h2>

        <p>I hope to support other tools&mdash;such as <a
        href=\"http://github.com/technomancy/leiningen\">Leiningen</a>""&mdash;as well.</p>
        <h2>Isn't this just a Clojure clone of <a href=\"http://gemcutter.org/\">gemcutter.org</a>?</h2>
        <p>Why mess with a good idea? :-)</p>"))

(defn dashboard [account]
  (html-doc account "Dashboard"
    [:h1 (str "Dashboard (" (h account) ")")]
    [:h2 "Your jars"]
    (unordered-list (map :jar_name (jars-by-user account)))
    (link-to "/help" "add new jar")
    [:h2 "Your groups"]
    (unordered-list (find-groups account))))

(defn not-found-doc []
  (html [:h1 "Page not found"]
        [:p "Thundering typhoons!  I think we lost it.  Sorry!"]))

(defmacro with-account [body]
  `(if-let [~'account (~'session :account)]
     (do ~body)
     (redirect-to "/login")))

(defmacro try-account [body]
  `(let [~'account (~'session :account)]
     (do ~body)))

(defroutes clojars-app
  (GET "/profile"
    (with-account
     (profile-form account)))
  (POST "/profile"
    (with-account
      (update-profile account params)))
  (GET "/login"
    (login-form))
  (POST "/login"
    (login params))
  (POST "/register"
    (register params))
  (GET "/register"
    (register-form))
  (GET "/logout"
    [(session-assoc :account nil)
     (redirect-to "/")])
  (GET "/"
    (try-account
     (if account
       (dashboard account)
       (index-page account))))
  (GET "/:user/:jarname"
    (try-account
     (show-jar account (:route-params request))))
  (GET "/:user"    
    (if-let [user (find-user ((:route-params request) :user))]
      (try-account
       (show-user account (:user user)))
      :next))
  (ANY "/*"
       (if-let [f (serve-file (params :*))]
         [{:headers {"Cache-Control" "max-age=3600"}} f]
         :next))
  (ANY "*"
    [404 (html-doc (session :account) "Page not found" (not-found-doc))]))

(decorate clojars-app
          (with-session)
          (with-db))

;(require 'swank.swank)
;(swank.swank/start-server "/dev/null" :port 4005)

;(run-server {:port 8000} "/*" (servlet clojars-app))

