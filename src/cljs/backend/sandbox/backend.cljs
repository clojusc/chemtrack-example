(ns sandbox.backend
  (:require [cljs.core.async :as async :refer [<! >! close!]]
            [cljs.nodejs :as nodejs]
            [cljs.reader :refer [read-string]]
            [clojure.string :as string]
            [clojusc.cljs-tools :as tools]
            [clojusc.twig :as twig]
            [eulalie.creds]
            [fink-nottle.sns :as sns]
            [fink-nottle.sqs.channeled :as sqs]
            [sandbox.backend.util :as util]
            [sandbox.creds]
            [taoensso.timbre :as log])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(nodejs/enable-util-print!)

(defonce express (nodejs/require "express"))
(defonce express-ws (nodejs/require "express-ws"))
(defonce http (nodejs/require "http"))

(defn sns-push-loop! [creds topic-id sightings-in]
  (go
    (loop []
      (let [item (<! sightings-in)]
        (sns/publish-topic! creds topic-id {:default (pr-str item)})
        (recur)))))

(defn sqs-incoming!
  [{:keys [max-recent recent deletes]}
   {:keys [body] :as message} results]
  (when-not body
    (log/error "Body undefined! Got message data:" message))
  (let [body (read-string body)]
    (swap! recent util/conj+evict body max-recent)
    (go
      (>! results body)
      (>! deletes message)
      (close! results))))

(defn connect-channels!
  [{:keys [port topic-name creds max-recent] :as config}
   {:keys [sightings-out sightings-in recent] :as channels}]
  (go
    (let [{:keys [queue-id topic-id]} (<! (util/topic-to-queue! config))]
      (sns-push-loop! creds topic-id sightings-in)
      (let [{deletes :in-chan} (sqs/batching-deletes creds queue-id)]
        (async/pipeline-async
          1
          sightings-out
          (partial sqs-incoming! {:deletes deletes
                                  :max-recent recent
                                  :recent recent})
          (sqs/receive! creds queue-id))))))

(defn make-sightings-handler [{:keys [sightings-out sightings-in recent]}]
  (let [sightings-out* (async/mult sightings-out)]
    (fn [websocket _]
      (let [from-client (async/chan 1 (map util/sighting-in))
            to-client (async/chan 1 (map util/sighting-out))]
        (util/channel-websocket! websocket to-client from-client)
        (async/pipe from-client sightings-in false)
        (go
          (<! (async/onto-chan to-client @recent false))
          (async/tap sightings-out* to-client))))))

(defn register-routes [app channels]
  (doto app
    (.use (.static express "resources/public"))
    (.ws "/sightings" (make-sightings-handler channels))))

(defn make-server [app]
  (let [server (.createServer http app)]
    (express-ws app server)
    server))

(defn get-port [default]
  (or (aget js/process "env" "PORT") default))

(defn -main [& [{:keys [port]
                 :or {port (get-port 8080)}}]]
  (twig/set-level! '[sandbox] :debug)
  (let [channels {:sightings-out (async/chan)
                  :sightings-in (async/chan)
                  :recent (atom #queue [])}
        app (express)
        server (make-server app)
        creds (sandbox.creds/load)
        ;creds (eulalie.creds/env)
        ]
    (log/debug (str "Using creds: " creds))
    (register-routes app channels)
    (connect-channels!
     {:port port
      :topic-name "sandbox-workflow"
      :creds creds
      :max-recent 10}
     channels)
    (.listen server port)))

(set! *main-cli-fn* -main)
