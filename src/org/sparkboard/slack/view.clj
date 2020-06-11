(ns org.sparkboard.slack.view
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [org.sparkboard.js-convert :refer [kw->js clj->json]]
            [org.sparkboard.slack.api :as slack]
            [taoensso.timbre :as log]
            [org.sparkboard.slack.hiccup :as hiccup]
            [org.sparkboard.util :as u]
            [clojure.set :as set])
  (:import (clojure.lang Atom)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; formatting helpers

(defn truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))

(defn slack-channel-namify [s]
  (-> s
      (str/replace #"\s+" "-")
      (str/lower-case)
      (str/replace #"[^\w\-_\d]" "")
      (truncate 70)))

(defn blockquote [text]
  (str "> " (str/replace text "\n" "\n> ")))

(defn link [text url]
  (str "<" url "|" text ">"))

(defn channel-link [id]
  (str "<#" id ">"))

(defn mention [user-id]
  (str "<@" user-id "> "))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; normalized handling of input values

(defn as-kw [x] (cond-> x (not (keyword? x)) (keyword)))

(defn action-value [action]
  (case (:type action)
    ("checkboxes"
      "multi_external_select"
      "multi_static_select") (->> (:selected_options action)
                                  (map :value)
                                  (into #{}))
    "multi_users_select" (set (:selected_users action))
    "multi_conversations_select" (set (:selected_conversations action))
    "multi_channels_select" (set (:selected_channels action))
    ("static_select"
      "external_select"
      "radio_buttons"
      "overflow") (-> action :selected_option :value)
    "plain_text_input" (:value action)
    "users_select" (:selected_user action)
    "datepicker" (:selected_date action)
    "button" nil
    (do
      (log/warn :not-parsing-action action)
      action)))

(defn actions-values [actions]
  (->> actions
       (reduce (fn [values action]
                 (assoc values (keyword (:action_id action)) (action-value action))) {})))

(defn input-values
  "Returns all of the normalized values from a view's input blocks"
  [view]
  (->> view
       :state
       :values
       vals
       (apply merge)
       (reduce-kv (fn [m k action]
                    (assoc m (keyword (name k)) (action-value action))) {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIP - api for making views with local state

(defonce registry (atom {}))
(comment
  (reset! registry nil))
;; the following is WIP
;; issues TBD:
;; - can be extended to support `home` screens as well as modals
;; - a way expose action-ids for opening modals / updating home screen
;; - unsure about using an atom for this api - may change action-fns to
;;   be pure functions of [state, value, context] => new state

(defn view-api
  ([method hiccup opts]
   (view-api method hiccup opts (meta hiccup)))
  ([method hiccup opts context]
   {:pre [context]}
   (slack/web-api method
                  (->> {:view hiccup}
                       (merge opts)
                       #_(reduce-kv (fn [m k v] (cond-> m (some? v) (assoc k v))) {})))))

(defn trigger [context]
  {:post [(some? %)]}
  (-> context :slack/payload :trigger_id))

(defn initial-state [view context] (:initial-state (meta view) (:initial-state context)))

(defn open!
  "Opens a modal"
  [view context]
  (view-api "views.open"
            (view (assoc context :state (initial-state view context)))
            {:trigger_id (trigger context)}))

(defn push!
  "Pushes new modal to the stack"
  [view context]
  (view-api "views.push"
            (view (assoc context :state (initial-state view context)))
            {:trigger_id (trigger context)}))

(defn replace!
  "Replaces modal at top of stack"
  [view context]
  (let [{:keys [hash id]} (-> context :slack/payload :view)]
    (view-api "views.update"
              (view (assoc context :state (initial-state view context)))
              {:hash hash
               :view_id id
               :trigger_id (trigger context)})))

(defn home!
  "Set home tab for user-id"
  [view context user-id]
  {:pre [user-id]}
  (view-api "views.publish"
            (view (assoc context :state (initial-state view context)))
            {:user_id user-id}))

(defn handle-home-opened! [view context]
  (view-api "views.publish"
            (view (assoc context :state (or (-> context :slack/payload :view :private_metadata)
                                            (initial-state view context))))
            {:user_id (:slack/user-id context)}))

(def ^:dynamic *view-opts* nil)

(defn handle-block-action
  "Calls a block action with [context, state-atom, block-value]"
  [context view action-id action-fn]
  (let [{:keys [hash id] prev-state :private_metadata} (-> context :slack/payload :view)
        state-atom (atom prev-state)
        actions (-> context :slack/payload :actions)
        values (-> context :slack/payload :actions actions-values)
        value (get values action-id)
        _ (binding [*view-opts* (meta view)]
            (action-fn (assoc context
                         :state state-atom
                         :action-values values
                         :block-id (-> actions first :block_id)
                         :value (get values action-id)
                         :view view)))
        next-state @state-atom]
    (log/trace action-id value {:prev-state prev-state :next-state next-state})
    (when (and next-state (not= prev-state next-state))
      (view-api "views.update"
                (view (assoc context :state next-state))
                {:hash hash
                 :view_id id
                 :trigger_id (trigger context)}))))

(defn return-json [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (clj->json body)})

(defn handle-form-callback
  "Calls a form action with [context, state-atom, input-values]"
  [context view kind form-fn]
  (let [rsp-view (-> context :slack/payload :view)
        result (binding [*view-opts* (meta view)]
                 (form-fn (assoc context
                            :state (atom (:private_metadata rsp-view))
                            :input-values (input-values rsp-view))))]
    (case kind
      :close result
      :submit (when-let [[action view] (u/guard result vector?)]
                (do (assert (and (#{:push :update} action)
                                 "on-submit should return [:push <view>] or [:update <view>] or nil"))
                    (return-json 200
                                 {:response_action action
                                  :view (hiccup/blocks view)}))))))

(defn id
  "Scopes an id to current view"
  ;; actions need globally-unique names, so we append them to their parent modal's name
  ([k]
   {:pre [*view-opts*]}
   (id (::view-name *view-opts*) k))
  ([view-name k]
   (if (simple-keyword? k)
     (keyword view-name (name k))
     k)))

(defn view-fn [{:as view-opts ::keys [view-name render-fn]}]
  (with-meta
    (fn [ctx]
      (let [ctx (update ctx :state #(cond-> % (not (instance? Atom %)) (atom)))
            hiccup (binding [*view-opts* view-opts] (render-fn ctx))
            private-metadata @(:state ctx)
            tag (first hiccup)]
        (cond-> hiccup
                (#{:modal} tag) (update-in [1 :callback-id] #(or % view-name))
                (#{:modal :home} tag) (with-meta ctx)
                (and (#{:modal :home} tag)
                     (seq private-metadata)) (assoc-in [1 :private_metadata] @(:state ctx)))))
    view-opts))

(defn make-view* [{:as view-opts
                   ::keys [view-name actions render-fn]
                   :keys [on-submit
                          on-close]}]
  (let [view (view-fn view-opts)
        handlers (merge (reduce-kv (fn [m action-name action-fn]
                                     (let [id (id view-name action-name)]
                                       (assoc m (as-kw id) #(handle-block-action % view id action-fn))))
                                   {}
                                   actions)
                        (when on-submit
                          {(keyword view-name "submit") ^:response-action? #(handle-form-callback % view :submit on-submit)})
                        (when on-close
                          {(keyword view-name "close") #(handle-form-callback % view :close on-submit)}))]
    (vary-meta view assoc ::handlers handlers)))

(defmacro defview
  "Defines a modal or home surface"
  [name-sym & args]
  (let [[doc args] (if (string? (first args))
                     [(first args) (rest args)]
                     [nil args])
        [options [argv & body]] (if (map? (first args))
                                  [(first args) (rest args)]
                                  [nil args])
        view-name (str name-sym)
        options (atom (set/rename-keys options {:actions ::actions}))
        body (walk/postwalk
               (fn [x]
                 (if-not (map? x)
                   x
                   (cond (:action-id x)
                         (cond (map? (:action-id x))
                               (let [[action-k action-form] (first (:action-id x))
                                     action-id (id view-name action-k)]
                                 (swap! options update ::actions assoc action-id action-form)
                                 (assoc x :action-id action-id))
                               (keyword? (:action-id x)) (update x :action-id (partial id view-name))
                               :else x)
                         (:on-submit x) (do (swap! options assoc :on-submit (:on-submit x))
                                            (dissoc x :on-submit))
                         (:on-close x) (do (swap! options assoc :on-close (:on-close x))
                                           (dissoc x :on-close))
                         :else x))) (last body))]
    (log/info :actions (::actions @options))
    `(do (def ~name-sym
           (make-view* (merge {::view-name ~view-name
                               ::render-fn (fn ~name-sym ~argv ~body)}
                              ~(deref options))))
         (swap! registry merge (::handlers (meta ~name-sym)))
         #'~name-sym)))
