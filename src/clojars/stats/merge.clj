(ns clojars.stats.merge)

(defn -main [& args]
  (let [stats (map (fn [filename]
                     (read (java.io.PushbackReader. (java.io.FileReader.
                                                     filename))))
                   args)]
    (prn (apply merge-with (partial merge-with +) stats))))