(ns clojars.test.integration.steps
  (:require [kerodon.core :refer :all]
            [clojure.test :as test]
            [clojure.java.io :as io]
            [clojars.scp :as scp]
            [clojars.config :as config]))

(defn login-as [state user password]
  (-> state
      (visit "/")
      (follow "login")
      (fill-in "Username or email:" user)
      (fill-in "Password:" password)
      (press "Login")))

(defn register-as
  ([state user email password ssh-key]
     (register-as state user email password password ssh-key))
  ([state user email password confirm ssh-key]
     (-> state
         (visit "/")
         (follow "register")
         (fill-in "Email:" email)
         (fill-in "Username:" user)
         (fill-in "Password:" password)
         (fill-in "Confirm password:" confirm)
         (fill-in "SSH public key:" ssh-key)
         (press "Register"))))

(defn scp-str-send-resource [filename]
  (let [contents (slurp (io/resource filename))]
    (str "C0644 " (count contents) " " filename "\n" contents)))

(def valid-ssh-key "ssh-test 0")

(defn find-user [key]
  (let [[string user]
        (re-find
         (re-pattern
          (str "command=\"ng --nailgun-port 8700 clojars.scp (.*)\",no-agent-forwarding,no-port-forwarding,no-pty,no-X11-forwarding " key "\n"))
         (slurp (:key-file config/config)))]
    user))

(defn scp [key & resources]
  (let [shim (proxy [com.martiansoftware.nailgun.NGContextShim] [])
        user (find-user key)
        bytes (java.io.ByteArrayOutputStream.)
        in (java.io.ByteArrayInputStream.
            (.getBytes (apply str (map scp-str-send-resource resources))
                       "UTF-8"))
        out (java.io.PrintStream. (java.io.ByteArrayOutputStream.))]
    (set! (.err shim) (java.io.PrintStream. bytes))
    (set! (.in shim) in)
    (set! (.out shim) out)
    (.setArgs shim (into-array [user]))
    (try
      (with-out-str
         (scp/nail shim))
      (catch Exception e))
    (.toString bytes)))
