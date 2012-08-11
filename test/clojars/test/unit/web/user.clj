(ns clojars.test.unit.web.user
  (:require [clojars.web.user :as user]
            [clojure.test :refer :all]))

(deftest ssh-key-validation-works
  (is (not (user/valid-ssh-key? "")))
  (is (not (user/valid-ssh-key? "foo")))
  (is (user/valid-ssh-key? "ssh-rsa 01234"))
  (is (user/valid-ssh-key? "ssh-rsa 01234 bar"))
  (is (user/valid-ssh-key? "ssh-rsa 01234\n"))
  (is (not (user/valid-ssh-key? "ssh-rsa 01234\nfoo")))
  (is (user/valid-ssh-key? "ssh-rsa 01234\nssh-rsa 042"))
  (is (user/valid-ssh-key? "ssh-rsa 01234 foo\n  \nssh-rsa 042 bar\n\n")))

