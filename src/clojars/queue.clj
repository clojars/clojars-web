(ns clojars.queue
  (:require [clojars.errors :as error]
            [durable-queue :as dq]
            [com.stuartsierra.component :as component]))

(defprotocol Queueing
  (enqueue! [_ queue msg])
  (register-handler [_ queue f])
  (remove-handler [_ queue])
  (stats [_]))

(defn handler-loop
  [reporter queues queue f run?]
  (future
    (try
      (while (get @run? queue)
        (let [task (dq/take! queues queue 100 ::timeout)]
          (when (not= ::timeout task)
            (try
              (f @task)
              (dq/complete! task)
              (catch Throwable e
                (error/report-error reporter e {:queue queue :message task})
                (dq/retry! task))))))
      (catch Throwable e
        (error/report-error reporter e)
        ;; TODO: limit # of restarts?
        (handler-loop reporter queues queue f run?)))))

(defrecord DurableQueueing [queues run-state error-reporter]
  Queueing
  (enqueue! [_ queue msg]
    (dq/put! queues queue msg))
  (remove-handler [_ queue]
    (swap! run-state dissoc queue))
  (register-handler [t queue f]
    (remove-handler t queue)
    (swap! run-state assoc queue true)
    (handler-loop error-reporter queues queue f run-state))
  (stats [_]
    (dq/stats queues))

  component/Lifecycle
  (start [t] t)
  (stop [t]
    (reset! run-state nil)
    t))

(defn queue-component [slab-dir]
  ;; fsync on every take to reduce chance of redoing a task on restart
  ;; our throughput needs are meager
  (map->DurableQueueing {:queues (dq/queues slab-dir {:fsync-take? true})
                         :run-state (atom nil)}))


