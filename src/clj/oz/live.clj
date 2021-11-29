(ns ^:no-doc oz.live
  (:require [hawk.core :as hawk]
            [taoensso.timbre :as log]
            [clojure.tools.reader :as reader]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            ;[oz.next :as next]
            [oz.impl.utils :as utils]
            [clojure.core.async :as async]))


(defn ppstr [x]
  (with-out-str (pprint/pprint x)))


;; The oppressive regime (deep state)

(defonce watchers (atom {}))



;; watching process ns form

(defn watch!
  "Takes a watch path, and a function with args (watch-path, event), where event looks like
  {:keys [file type filename watch-path]}"
  [watch-path f]
  (let [decorate-event (fn [{:as event :keys [file]}]
                         (assoc event
                                :filename (.getPath file)
                                :watch-path watch-path))]
    (when-not (get @watchers watch-path)
      (f (decorate-event {:kind :create :file (io/file watch-path)}))
      (let [watcher
            (hawk/watch! [{:paths [watch-path]
                           :handler (fn [_ event] (f (decorate-event event)))}])]
        (swap! watchers assoc-in [watch-path :watcher] watcher)
        ::success!))))


(defn process-ns-form
  [ns-form]
  ;; Separate out all of the "reference forms" from the rest of the ns declaration; we'll use this in a couple
  ;; different ways later
  (let [reference-forms (->> ns-form (filter seq?) (map #(vector (first %) (rest %))) (into {}))]
    ;; the namespace sym should always be the second item in the form
    {:ns-sym
     (second ns-form)
     :reference-forms
     (concat
       ;; We need to use explicit namespacing here for refer-clojure, since hasn't been
       ;; referenced yet, and always include this form as the very first to execute, regardless of whether it shows
       ;; up in the ns-form, otherwise refer, def, defn etc won't work
       [(concat (list 'clojure.core/refer-clojure)
                (:refer-clojure reference-forms))]
       ;; All other forms aside from :refer-clojure can be handled by changing keyword to symbol and quoting
       ;; arguments, since this is the corresponding call pattern from outside the ns-form
       (map
         (fn [[k v]]
           (concat (list (-> k name symbol))
                   (map #(list 'quote %) v)))
         (dissoc reference-forms :refer-clojure)))}))


(defn- process-form!
  [ns-sym form new-form-evals]
  (let [t0 (System/currentTimeMillis)
        result-chan (async/chan 1)
        timeout-chan (async/timeout 1000)
        long-running? (async/chan 1)
        error-chan (async/chan 1)]
    ;; Create a timeout on the result, and log a "in processing" message if necessary
    (async/go
      (let [[_ chan] (async/alts! [result-chan timeout-chan error-chan])]
        (when (= chan timeout-chan)
          (log/info (utils/color-str utils/ANSI_YELLOW "Long running form being processed:\n" (ppstr form)))
          (async/>! long-running? true))))
    ;; actually run the code
    (try
      (binding [*ns* (create-ns ns-sym)]
        (let [result (eval form)]
          ;; put the result on the result chan, to let the go block above know we're done
          (async/>!! result-chan :done)
          ;; keep track of successfully run forms, so we don't redo work that completed
          (swap! new-form-evals conj {:form form :eval result})
          ;; If long running, log out how long it took
          (when (async/poll! long-running?)
            (println (utils/color-str utils/ANSI_YELLOW "Form processed in: " (/ (- (System/currentTimeMillis) t0) 1000.0) "s")))))
      (catch Throwable t
        (log/error (utils/color-str utils/ANSI_RED "Error processing form:\n" (ppstr form)))
        (log/error t)
        (async/>!! error-chan t)
        (throw t)))))


(defn compile-forms-hiccup
  [form-evals]
  [:div
   (->> form-evals
        ;; We only render as hiccup forms which are vector literals (for now)
        (filter (fn [{:keys [form]}]
                  (vector? form)))
        (map :eval))])

(defn reload-file! [{:keys [kind file]}]
  ;; ignore delete (some editors technically delete the file on every save!)
  (when (#{:modify :create} kind)
    (let [filename (utils/canonical-path file)
          contents (slurp file)
          {:keys [last-contents]} (get @watchers filename)]
      ;; This has a couple purposes, vs just using the (seq diff-forms) below:
      ;; * forces at least a check if the file is changed at all before doing all the rest of the work below
      ;;   (not sure how much perf benefit there is here)
      ;; * there's sort of a bug and a feature: the (seq diff-forms) bit below ends up not working if
      ;;   `forms` doesn't have everything, which will be the case if(f?) there was an error running
      ;;   things. This means changing a ns form (e.g.) will trigger an update on forms that failed
      ;; * this prevents the weird multiple callback issue with the way vim saves files creating 3/4 change
      ;;   events
      (when (not= contents last-contents)
        (swap! watchers assoc-in [filename :last-contents] contents)
        (let [forms (reader/read-string (str "[" contents "]"))
              last-form-evals (get-in @watchers [filename :form-evals])
              ;; gather all the forms that differ from the last run, and any that follow a differing form
              ;; this may evolve into a full blown dependency graph thing
              diff-forms (->> (map vector
                                   (drop 1 forms)
                                   (concat (map :form last-form-evals)
                                           (repeat nil)))
                              (drop-while (fn [[f1 f2]] (= f1 f2)))
                              (map first))
              {:keys [ns-sym reference-forms]} (process-ns-form (first forms))
              new-form-evals (atom [])]
              ;; eventually add a final-form evaluation?
              ;final-form (atom nil)]
          ;; if there are differences, then do the thing
          (when (seq diff-forms)
            (log/info (utils/color-str utils/ANSI_GREEN "Reloading file: " filename))
            ;; Evaluate the ns form's reference forms
            (binding [*ns* (create-ns ns-sym)]
              (eval (concat '(do) reference-forms)))
            ;; Evaluate each of the following forms thereafter differ from the last time we successfully ran
            (try
              (doseq [form diff-forms]
                (process-form! ns-sym form new-form-evals))
              (log/info (utils/color-str utils/ANSI_GREEN "Done reloading file: " filename "\n"))
              (catch Exception _
                (log/error (utils/color-str utils/ANSI_RED "Unable to process all of file: " filename "\n"))))
            ;; Update forms in our state atom, only counting those forms which successfully ran
            (let [base-form-evals (take (- (count forms) (count diff-forms) 1) last-form-evals)
                  form-evals (concat base-form-evals @new-form-evals)
                  final-eval (:eval (last form-evals))]
              (swap! watchers update filename #(merge % {:form-evals form-evals :final-eval final-eval}))
              (compile-forms-hiccup form-evals))))))))



;; Plugging this all together into the final API calls.
;; This will all switch over to reload-file-next! when it's ready.

(defn live-reload!
  "Watch a clj file for changes, and re-evaluate only those lines which have changed, together with all following lines.
  Is not sensitive to whitespace changes, and will also always rerun reference forms included in the ns declaration."
  [filename]
  ;; QUESTION What happens here when this is a path?
  (when-not (get @watchers filename)
    (log/info "Starting live reload on file:" filename))
  (watch! filename reload-file!))


(defn kill-watcher!
  "Kill the corresponding watcher thread."
  ;; TODO Need to get this to properly kill a whole dir of watchers?
  [filename]
  (hawk/stop! (get-in @watchers [filename :watcher]))
  (swap! watchers dissoc filename))


(defn kill-watchers!
  "Kill all watcher threads if no args passed, or just watcher threads for specified filenames if specified."
  ([] (kill-watchers! (keys @watchers)))
  ([filenames]
   (doseq [filename filenames]
     (try
       (kill-watcher! filename)
       (catch Exception _
         :pass)))))


(comment
  (kill-watchers!)
  (live-reload! "scratch/watchtest.clj")
  :end-comment)

