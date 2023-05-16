(ns sparkboard.build
  (:require [babashka.process :as bp]
            [clojure.repl.deps :as deps]
            [clojure.pprint :refer [pprint]]
            [re-db.in-memory :as db]))

(defn start
  {:shadow/requires-server true}
  []
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) :web)
  ((requiring-resolve 'sparkboard.server.core/-main)))

(defn tailwind-dev!
  {:shadow.build/stage :flush}
  [state]
  (defonce _tsc (bp/process
                 {:in :inherit
                  :out :inherit
                  :err :inherit
                  :shutdown bp/destroy-tree}
                 "npx tailwindcss -w -i src/sparkboard.css -o resources/public/sparkboard.css"))
  state)

(defn tailwind-release!
  {:shadow.build/stage :flush}
  [state]
  (bp/sh "npx tailwindcss -i src/sparkboard.css -o resources/public/sparkboard.css")
  state)


(comment
 (start)
 (clerk/clear-cache!))

(defn aot [_]
  (require 'sparkboard.server.core)
  (compile 'sparkboard.server.core))

(defn uberjar [_]
  (let [deps (clojure.edn/read-string (slurp "deps.edn"))]
    ((requiring-resolve 'uberdeps.api/package) deps "target/sparkboard.jar"
     {:main-class "sparkboard.server"
      :aliases [:datalevin]})))


(comment

 (deps/sync-deps)

 (require '[sparkboard.migration.one-time :as one-time]
          '[datalevin.core :as dl]
          '[re-db.api :as db]
          '[sparkboard.schema :as sb.schema])

 ;; DATA IMPORT - copies to ./.db
 (one-time/fetch-mongodb)
 (one-time/fetch-firebase)
 (one-time/fetch-accounts)

 ;; RESHAPED ENTITIES
 (def entities (one-time/all-entities))
 (comment
  (into #{} (filter (comp #{"id"} name)) (mapcat keys entities)))

 ;; COMMIT TO DB - note, you may wish to
 (dl/clear conn)                                            ;; NOTE - after this, re-run `def conn` in sparkboard.datalevin

 (defn try-transact! [txs]
   (doseq [tx txs]
     (try
       (db/transact! [tx])
       (catch Exception e
         (pprint [:failed tx])
         (throw e)))))

 ;; transact schema
 (db/merge-schema! sb.schema/sb-schema)
 ;; upsert lookup refs
 (db/transact! (mapcat sb.schema/unique-keys entities))
 ;; transact entities
 (db/transact! (one-time/all-entities))

 ;; add entities, print and stop if one fails
 (doseq [e entities]
   (try (db/transact! [e])
        (catch Exception e!
          (prn e)
          (throw e!))))

 (one-time/explain-errors!)



 )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure