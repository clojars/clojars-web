(ns clojars.tools.merge-stats
  (:import (java.io FileReader PushbackReader)))

(defn -main [& args]
  (let [stats (map (fn [filename]
                     (read (PushbackReader. (FileReader.
                                                     filename))))
                   args)]
    (prn (apply merge-with (partial merge-with +) stats))))