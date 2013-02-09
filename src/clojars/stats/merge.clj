(ns clojars.stats.merge)

(defn -main [& args]
  (let [stats (map (fn [filename]
                     (read (java.io.PushbackReader. (java.io.FileReader.
                                                     filename))))
                   args)]
    (apply merge-with (partial merge-with +) stats)))