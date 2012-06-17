(ns chat-demo.core
  (:require [net.thegeez.browserchannel :as browserchannel]
            [net.thegeez.jetty-async-adapter :as jetty]
            [ring.middleware.resource :as resource]
            [ring.middleware.file-info :as file]
            [clj-redis.client :as redis]))

(def db (redis/init {:url (get (System/getenv) "REDISTOGO_URL" "redis://127.0.0.1:6379")}))

(def channel (get (System/getenv) "CHANNEL" "*"))

(defn handler [req]
   {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello World"})

(def clients (atom #{}))

(def dev-app
  (-> handler
      (resource/wrap-resource "dev")
      (resource/wrap-resource "public")
      file/wrap-file-info
    (browserchannel/wrap-browserchannel {:base "/channel"
                                         :on-session
                                         (fn [session-id req]
                                           (browserchannel/add-listener
                                             session-id
                                             :close
                                             (fn [reason]
                                               (println "session " session-id " disconnected: " reason)
                                               (swap! clients disj session-id)
                                               (doseq [client-id @clients]
                                                 (browserchannel/send-map client-id {"msg" (str "client " session-id " disconnected " reason)}))))
                                           (browserchannel/add-listener
                                             session-id
                                             :map
                                             (fn [map]
                                               (println "session " session-id " sent")
                                               (doseq [client-id @clients]
                                                 (browserchannel/send-map client-id map))))
                                           (println "session " session-id " connected")
                                           (swap! clients conj session-id)
                                           (doseq [client-id @clients]
                                             (browserchannel/send-map client-id {"msg" (str "client " session-id " connected")})))})))

(defn -main [& args]
  (println "Using Jetty adapter")
  (jetty/run-jetty-async #'dev-app {:port (Integer.  (or (System/getenv "PORT") 4444)) :join? false})
  (redis/subscribe db [channel]
                   (fn [ch msg]
                     (doseq [client-id @clients]
                       (browserchannel/send-map client-id {"msg" (str "redis message " msg)}))
                     ))
)
