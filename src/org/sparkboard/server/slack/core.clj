(ns org.sparkboard.server.slack.core
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [org.sparkboard.js-convert :refer [json->clj]]
            [org.sparkboard.server.env :as env]
            [taoensso.timbre :as log])
  (:import [java.net.http HttpClient HttpRequest HttpClient$Version HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.net URI]))

(def base-uri "https://slack.com/api/")

(defonce ^{:doc "Slack Web API RPC specification"
           :lookup-ts (java.time.LocalDateTime/now (java.time.ZoneId/of "UTC"))}
  web-api-spec
         (delay
           (json/read-value (slurp                          ;; canonical URL per https://api.slack.com/web#basics#spec
                              "https://api.slack.com/specs/openapi/v2/slack_web.json"))))

(defn http-verb [family-method]
  (case (ffirst (get-in @web-api-spec
                        ["paths" (if-not (#{\/} (first family-method))
                                   (str "/" family-method)
                                   family-method)]))
    "get"  client/get
    "post" client/post))

;; TODO consider https://github.com/gnarroway/hato
;; TODO consider wrapping Java11+ API further
(defn web-api
  ;; because `clj-http` fails to properly pass JSON bodies - it does some unwanted magic internally
  ([family-method config]
   (let [request (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str base-uri family-method)))
                     (.header "Content-Type" "application/json; charset=utf-8")
                     (.header "Authorization" (str "Bearer " (:auth/token config)))
                     (.GET)
                     (.build))
         clnt (-> (HttpClient/newBuilder)
                  (.version HttpClient$Version/HTTP_2)
                  (.build))
         rsp (json->clj (.body (.send clnt request (HttpResponse$BodyHandlers/ofString))))]
     (log/debug "[web-api] GET rsp:" rsp)
     (when-not  (:ok rsp)
       (throw (ex-info (str "web-api failure: slack/" family-method) {:rsp rsp :config config})))
     rsp))
  ([family-method config body]
   (log/debug "[web-api] body:" body)
   (let [request (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str base-uri family-method)))
                     (.header "Content-Type" "application/json; charset=utf-8")
                     (.header "Authorization" (str "Bearer " (:auth/token config)))
                     (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body)))
                     (.build))
         clnt (-> (HttpClient/newBuilder)
                  (.version HttpClient$Version/HTTP_2)
                  (.build))
         rsp (json->clj (.body (.send clnt request (HttpResponse$BodyHandlers/ofString))))]
     (when-not  (:ok rsp)
       (throw (ex-info (str "web-api failure: slack/" family-method) {:rsp rsp :config config})))
     (log/debug "[web-api] POST rsp:" rsp)
     rsp)))

(comment
  (http-verb "/users.list")

  )

(def channel-name
  (memoize
   (fn [channel-id token]
     (get (into {}
                (map (juxt :id :name_normalized)
                     (:channels (web-api "channels.list"
                                         {:auth/token token}))))
          channel-id))))
