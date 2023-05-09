(ns sparkboard.views.ui
  (:require [yawn.view :as v]
            [clojure.walk :as walk]
            [inside-out.macros]))

(defn wrap-tr [expr]
  (walk/postwalk (fn [x] (if (and (keyword? x)
                                  (= "tr" (namespace x)))
                           `(~'sparkboard.i18n/tr ~x)
                           x))
                 expr))

(defmacro tr [expr]
  (wrap-tr expr))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs (tr ~expr)))}
   name
   args))

(defmacro x [& args]
  (let [args (wrap-tr args)]
    `(do ~@(butlast args)
         (~'yawn.view/x ~(last args)))))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x (do ~@body))]))