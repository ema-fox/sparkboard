(ns sparkboard.util
  (:refer-clojure :exclude [ref])
  (:require #?(:clj [backtick])
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.hooks :as hooks]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.schema :as s])
  #?(:cljs (:require-macros sparkboard.util)))

(defn guard [x f]
  (when (f x) x))

(defn assoc-some [m k v]
  (if (some? v)
    (assoc m k v)
    m))

(defn assoc-seq [m k v]
  (if (seq v)
    (assoc m k v)
    m))

(defn update-some [m updaters]
  (reduce-kv (fn [m k f]
               (let [v (get m k ::not-found)]
                 (if (= ::not-found v)
                   m
                   (assoc m k (f v))))) m updaters))

(defn update-some-paths [m & pvs]
  (reduce (fn [m [path f]]
            (if-some [v (get-in m path)]
              (assoc-in m path (f v))
              m))
          m
          (partition 2 pvs)))


(defn ensure-prefix [s prefix]
  (if (str/starts-with? s prefix)
    s
    (str prefix s)))

(defn some-str [s] (guard s (complement str/blank?)))

(defn find-first [coll pred]
  (reduce (fn [_ x] (if (pred x) (reduced x) _)) nil coll))

(defn prune
  "Removes nil values from a map recursively"
  [m]
  (reduce-kv (fn [m k v]
               (if (map? v)
                 (assoc-seq m k (prune v))
                 (if (or (nil? v) (and (coll? v) (empty? v)))
                   m
                   (assoc m k v)))) {} m))

(defn keep-changes
  "Removes nil values from a map, not recursive"
  [old new]
  (reduce-kv (fn [m k v]
               (if (= v (get old k))
                 (dissoc m k)
                 (assoc m k v))) {} new))

(defn select-as [m kmap]
  (reduce-kv (fn [out k as]
               (if (contains? m k)
                 (assoc out as (get m k))
                 out)) {} kmap))

(defmacro p-when [test & body]
  `(p/let [result# ~test]
     (when result#
       ~@body)))

(defmacro template [x]
  `(~'backtick/template ~x))

(defn lift-key [m k]
  (merge (dissoc m k)
         (get m k)))

(defn dequote [id]
  (if (and (list? id) (= 'quote (first id)))
    (second id)
    id))

#?(:clj
   (defn memo-fn-var [query-var]
     (memo/fn-memo [& args]
       (r/reaction
         (let [f (hooks/use-deref query-var)]
           (apply f args))))))