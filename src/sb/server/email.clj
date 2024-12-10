(ns sb.server.email)

(defn send! [{:keys [to subject body]}]
  (println "Would send email to:" to)
  (println "Subject:" subject)
  (println body))


(defn send-to-account!
  "Sends an email with greetings added to the email address of `account` if that email address is verified.
  Use this function instead of send `send!` if possible."
  [{:keys [account subject body]}]
  (if (:account/email-verified? account)
    (send! {:to (:account/email account)
            :subject subject
            :body (binding [i/*selected-locale* (:account/locale account)]
                    (t :tr/email-template [(:account/display-name account)
                                           body]))})))


(comment
  (send! {:to "ema@mailbox.org"
         :subject "test"
         :body "Hello!"})
  )
