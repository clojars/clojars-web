(ns clojars.event
  "Event emission and handling using SQS."
  (:require
   [clojars.aws-util :as aws-util]
   [clojars.errors :as errors]
   [clojure.edn :as edn]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [com.stuartsierra.component :as component]))

(set! *warn-on-reflection* true)

(defn- sqs-client
  [{:as _config :keys [credentials endpoint region]}]
  (doto (aws/client (cond-> {:api :sqs}
                      credentials (assoc :credentials-provider (credentials/basic-credentials-provider credentials))
                      endpoint    (assoc :endpoint-override endpoint)
                      region      (assoc :region region)))
    (aws/validate-requests true)))

(defprotocol EventEmitter
  (-emit [this payload]))

(defrecord SQSEmitter [config]
  component/Lifecycle
  (start [this]
    (assoc this
           :sqs-client (sqs-client config)))
  (stop [this] this)

  EventEmitter
  (-emit [this payload]
    (aws-util/invoke (:sqs-client this) :SendMessage
                     {:QueueUrl (:queue-url config)
                      :MessageBody (binding [*print-length* nil]
                                     (pr-str payload))})
    true))

(defonce ^:private handlers (atom {}))

(defn- sqs-receive-loop
  [running? error-reporter sqs-client queue-url message-wait-timeout-seconds]
  (while @running?
    (try
      ;; This will sleep up to message-wait-timeout-seconds waiting on a message
      ;; if it is provided
      (let [res (aws-util/invoke sqs-client :ReceiveMessage
                                 (cond-> {:QueueUrl queue-url}
                                   message-wait-timeout-seconds
                                   (assoc :WaitTimeSeconds message-wait-timeout-seconds)))]
        (doseq [{:keys [Body ReceiptHandle]} (:Messages res)
                :let [event (edn/read-string Body)]]
          (doseq [handler (vals @handlers)]
            ;; This inner try/catch catches the failure of a single handler. If
            ;; we let this bubble out, we wouldn't delete the message, and risk
            ;; running all the handlers again when possibly just one is failing.
            (try
              (handler event)
              (catch Exception e
                (errors/report-error error-reporter e))))
          ;; We don't consider handler failures worthy of retrying, given the
          ;; risk of rerunning handlers, so we always delete the message if we
          ;; make it this far. This means that in practice we'll never redrive a
          ;; message, since this delete will only not get called if we can't edn
          ;; parse the message.
          (aws-util/invoke sqs-client :DeleteMessage
                           {:QueueUrl queue-url
                            :ReceiptHandle ReceiptHandle})))
      (catch Exception e
        (errors/report-error error-reporter e)
        ;; We throw the exception to trigger the uncaughtExceptionHandler, which
        ;; will cause the process to exit. Systemd will restart the process,
        ;; giving us a new receive loop. See `clojars.errors`.
        (throw e)))))

(defrecord SQSReceiver [error-reporter config]
  component/Lifecycle
  (start [this]
    (let [running? (atom true)]
      (assoc this
             :running? running?
             :thread (doto (Thread.
                            (fn []
                              (sqs-receive-loop running? error-reporter
                                                (sqs-client config)
                                                (:queue-url config)
                                                (:message-wait-timeout config))))
                       (.start)))))
  (stop [this]
    (when-some [running? (:running? this)]
      (reset! running? false)
      (.join ^Thread (:thread this) 60000))
    this))

(defn new-sqs-receiver
  [config]
  (map->SQSReceiver {:config config}))

(defn emit
  "Emits an event of `type` with `data`. Returns true if the event was
  successfully queued."
  [emitter type data]
  (-emit emitter {::type type ::data data}))

(defn new-sqs-emitter
  [config]
  (map->SQSEmitter {:config config}))

(defn- wrap
  [f]
  (fn [{::keys [type data]}]
    (f type data)))

(defn add-handler
  "Add an event handler. `f` is a function of two arguments that will be
  passed the type of the event and the event data. Returns a value
  that can be passed to `remove-handler` to remove the handler."
  [f]
  (let [f (wrap f)
        key (gensym "event-handler")]
    (swap! handlers assoc key f)
    key))

(defn remove-handler
  "Removes an event handler. Should be passed the return value of `add-handler`."
  [key]
  (swap! handlers dissoc key)
  nil)

(defrecord EventHandler [handler]
  component/Lifecycle
  (start [this]
    (assoc this :key (add-handler (partial handler this))))
  (stop [{:as this :keys [key]}]
    (remove-handler key)
    (dissoc this :key)))

(defn handler-component
  "Returns a new event handler component. `handler` is a three arg
  function that will be passed the component (to allow access to other
  components), the event type, and the event data."
  [handler]
  (->EventHandler handler))
