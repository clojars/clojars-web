(ns clojars.event
  "Rudimentary event emission and handling using tap>."
  (:require [com.stuartsierra.component :as component]))

(defn emit
  "Emits an event of `type` with `data`. Returns true if the event was
  successfully queued."
  [type data]
  (tap> {::type type
         ::data data}))

(defn- wrap
  [f]
  (fn [{::keys [type data]}]
    (f type data)))

(defn add-handler
  "Add an event handler. `f` is a function of two arguments that will be
  passed the type of the event and the event data. Returns a value
  that can be passed to `remove-handler` to remove the handler."
  [f]
  (let [f (wrap f)]
    (add-tap f)
    f))

(def remove-handler
  "Removes an event handler. Should be passed the return value of `add-handler`."
  remove-tap)

(defrecord EventHandler
    [handler]
  component/Lifecycle
  (start [this]
    (assoc this :f (add-handler (partial handler this))))
  (stop [{:as this :keys [f]}]
    (remove-handler f)
    (dissoc this :f)))

(defn handler-component
  "Returns a new event handler component. `handler` is a three arg
  function that will be passed the component (to allow access to other
  components), the event type, and the event data."
  [handler]
  (->EventHandler handler))
