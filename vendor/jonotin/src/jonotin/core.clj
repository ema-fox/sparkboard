(ns jonotin.core
  (:require [clojure.java.io :as io])
  (:import (org.threeten.bp Duration)
           (com.google.protobuf ByteString)
           (com.google.api.gax.batching BatchingSettings)
           (com.google.api.gax.core FixedCredentialsProvider)
           (com.google.api.core ApiFuture
                                ApiFutureCallback
                                ApiFutures
                                ApiService$Listener)
           (com.google.common.util.concurrent MoreExecutors)
           (com.google.cloud.pubsub.v1 Publisher
                                       AckReplyConsumer
                                       MessageReceiver
                                       Subscriber)
           (com.google.pubsub.v1 ProjectTopicName
                                 PubsubMessage
                                 ProjectSubscriptionName
                                 AcknowledgeRequest
                                 PullRequest)
           (com.google.api.gax.core FixedCredentialsProvider)
           (com.google.auth.oauth2 ServiceAccountCredentials)))

(defn credentials-from-string [credentials]
  (-> credentials .getBytes io/input-stream (ServiceAccountCredentials/fromStream)))

(defn subscribe! [{:keys [project-name subscription-name handle-msg-fn handle-error-fn credentials]}]
  (let [subscription-name-obj (ProjectSubscriptionName/format project-name subscription-name)
        msg-receiver (reify MessageReceiver
                       (receiveMessage [_ message consumer]
                         (let [data (.toStringUtf8 (.getData message))]
                           (try
                            (handle-msg-fn data)
                            (catch Exception e
                              (if (some? handle-error-fn)
                                (handle-error-fn e)
                                (throw e)))
                            (finally
                              (.ack consumer))))))
        subscriber (cond-> (Subscriber/newBuilder subscription-name-obj msg-receiver)
                           credentials (.setCredentialsProvider (-> credentials
                                                                    credentials-from-string
                                                                    FixedCredentialsProvider/create))
                           true (.build))
        listener (proxy [ApiService$Listener] []
                   (failed [from failure]
                     (println "Jonotin failure with msg handling -" failure)))]
    (.addListener subscriber listener (MoreExecutors/directExecutor))
    (.awaitRunning (.startAsync subscriber))
    (.awaitTerminated subscriber)
    subscriber))

(defn publish! [{:keys [project-name topic-name messages]}]
  (when (> (count messages) 10000)
    (throw (ex-info "Message count over safety limit"
                    {:type :jonotin/batch-size-limit
                     :message-count (count messages)}))) 
  (let [topic (ProjectTopicName/of project-name topic-name)
        batching-settings (-> (BatchingSettings/newBuilder)
                              (.setRequestByteThreshold 1000)
                              (.setElementCountThreshold 10)
                              (.setDelayThreshold (Duration/ofMillis 1000))
                              .build)
        publisher (-> (Publisher/newBuilder topic)
                      (.setBatchingSettings batching-settings)
                      .build)
        callback-fn (reify ApiFutureCallback
                      (onFailure [_ t]
                                   (throw (ex-info "Failed to publish message"
                                                   {:type :jonotin/publish-failure
                                                    :message t})))
                      (onSuccess [_ result]
                                   ()))
        publish-msg-fn (fn [msg-str]
                         (let [msg-builder (PubsubMessage/newBuilder)
                               data (ByteString/copyFromUtf8 msg-str)
                               msg (-> msg-builder
                                       (.setData data)
                                       .build)
                               msg-future (.publish publisher msg)]
                           (ApiFutures/addCallback msg-future callback-fn (MoreExecutors/directExecutor))
                           msg-future))
        futures (map publish-msg-fn messages)
        message-ids (.get (ApiFutures/allAsList futures))]
    (.shutdown publisher)
    (.awaitTermination publisher 5 java.util.concurrent.TimeUnit/MINUTES)
    {:delivered-messages (count message-ids)}))
