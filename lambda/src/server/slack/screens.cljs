(ns server.slack.screens
  (:require [applied-science.js-interop :as j]
            [server.blocks :as blocks]))

(def main-menu
  [:section
   {:accessory [:button {:style "primary",
                         :action_id "admin:team-broadcast"
                         :value "click_me_123"}
                "Compose"]}
   "*Team Broadcast*\nSend a message to all teams."])

(defn home []
  [:home
   main-menu
   [:divider]
   [:section
    (str "_Last updated:_ "
         (-> (js/Date.)
             (.toLocaleString "en-US" #js{:dateStyle "medium"
                                          :timeStyle "medium"})))]])

(def shortcut-modal
  [:modal {:title "Broadcast"
           :blocks [main-menu]}])

(def team-broadcast-blocks
  (list
    [:section "Send a prompt to *all projects*."]
    [:divider]
    [:section
     {:block_id "sb-section1"
      :accessory [:conversations_select
                  {:placeholder [:plain_text "Select a channel..."],
                   :action_id "broadcast2:channel-select"
                   :filter {:include ["public" "private"]}}]}
     "*Post responses to channel:*"]
    [:input
     {:block_id "sb-input1"
      :element [:plain_text_input
                {:multiline true,
                 :action_id "broadcast2:text-input"
                 :initial_value "It's 2 o'clock! Please post a brief update of your team's progress so far today."}],
      :label [:plain_text "Message:"]}]))

(def team-broadcast-modal-compose
  [:modal {:title [:plain_text "Compose Broadcast"]
           :blocks team-broadcast-blocks
           :submit [:plain_text "Submit"]}])

(defn team-broadcast-message [msg]
  (list
   [:section {:text {:type "mrkdwn" :text msg}}]
   {:type "actions",
    :elements [[:button {:style "primary"
                         :text {:type "plain_text",
                                :text "Post an Update",
                                :emoji true},
                         :action_id "user:team-broadcast-response"
                         :value "click_me_123"}]]}))

(def team-broadcast-response
  [:modal {:title [:plain_text "Project Update"]
           :blocks (list
                    {:type "actions",
                     :elements [[:button {:text {:type "plain_text",
                                                 :text "Describe current status",
                                                 :emoji true},
                                          :action_id "user:team-broadcast-response-status"
                                          :value "click_me_123"}]
                                [:button {:text {:type "plain_text",
                                                 :text "Share achievement",
                                                 :emoji true},
                                          :action_id "user:team-broadcast-response-achievement"
                                          :value "click_me_456"}]
                                [:button {:text {:type "plain_text",
                                                 :text "Ask for help",
                                                 :emoji true},
                                          :action_id "user:team-broadcast-response-help"
                                          :value "click_me_789"}]]})
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-status
  [:modal {:title [:plain_text "Describe Current Status"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us what you've been working on:",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-achievement
  [:modal {:title [:plain_text "Share Achievement"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us about the milestone you reached:",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-help
  [:modal {:title [:plain_text "Request for Help"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Let us know what you could use help with. We'll try to lend a hand.",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(comment
  (blocks/parse team-broadcast-modal-compose)
  (blocks/parse [:md "hi"]))
