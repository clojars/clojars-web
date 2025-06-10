(ns kerodon.core)

(defmacro within [state selector & fns]
  `(-> (let [_# ~selector] ~state) ~@fns))
