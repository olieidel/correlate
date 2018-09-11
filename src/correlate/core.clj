(ns correlate.core
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clojure.data.csv :as data.csv]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [correlate.csv :as csv]
            [correlate.emfit-qs :as emfit-qs]
            [correlate.google-fit :as google-fit]
            incanter.charts
            incanter.core
            incanter.stats))

;; -- Magic Numbers ------------------------------------------------------------

(def ^:private *time-zone-offset* 1)


;; -- CSV Parsing --------------------------------------------------------------

(defn- parse-datetime
  "Parse a datetime string from a Google Sheet. Example: \"5/19/2018
  20:30:00\"."
  [datetime-str]
  (let [formatter (f/formatter "MM/dd/yyyy HH:mm:ss")]
    (-> (f/parse formatter datetime-str)
        ;; the datetimes are in Berlin time, so we manually set the
        ;; time zone to UTC+1. but then we want to see the times in
        ;; UTC time, so we convert the times to UTC time. in summary,
        ;; we just want to be dealing with utc times and subtract 1
        ;; hour. simple, right?

        ;; FIXME: hard-coded timezone!
        (t/from-time-zone (t/time-zone-for-offset *time-zone-offset*)))))


(defn- parse-event
  "Preprocess an event (string). Trim and replace spaces with dashes as
  otherwise vowpal-wabbit stumbles over them."
  [s]
  (-> (str/trim s)
      (str/replace " " "-")))


(defn- parse-category
  [s]
  (-> s preprocess-str keyword))


(def ^:private column-descriptions
  [{:key      :datetime
    :parse-fn parse-datetime}
   {:key      :category
    :parse-fn parse-category}
   {:key      :event
    :parse-fn parse-event}
   {:key      :value}])


(defn read-csv
  [csv-path]
  (csv/read-and-parse csv-path column-descriptions))


;; -- Advanced Parsing & Preprocessing -----------------------------------------

(defn- measurement?
  "Whether a row-entry is a measurement."
  [row-entry]
  (contains? #{:brain-fog :libido :extroversion :poop :weight :medical :stroop}
             (:category row-entry)))


(defn- remove-measurements
  "Remove all measurements from a list of rows."
  [rows]
  (remove measurement? rows))


(defn filter-measurements
  "Filter measurements, selected only those measurements which have the
  same `:category` as `category`."
  [rows category event]
  (filter #(and (measurement? %)
                (= category (:category %))
                (= event (:event %))
                (some? (:value %)))
          rows))


(defn counts [xs]
  (reduce (fn [acc x]
            (if (contains? acc x)
              (update acc x inc)
              (assoc acc x 1)))
          {}
          xs))


(defn remove-rare-events
  "Remove events (not measurements!) which occur rarely in the
  dataset. Rare occurance is defined as the count being <=
  `max-count`."
  [rows max-count]
  (let [events (remove-measurements rows)
        counts (counts (map :event events))
        events-to-keep (->> (remove (fn [[event count]]
                                      (<= count max-count))
                                    counts)
                            (map key)
                            (into #{}))]
    (filter #(or (measurement? %)
                 (contains? events-to-keep (:event %)))
            rows)))


;; -- Time Windowing -----------------------------------------------------------

(defn- interval-hours-before
  "Return a time interval. The right side (exclusive) is given by
  `datetime`, the left side (inclusive) is calculated by subtracting
  `hours-before` from datetime."
  [datetime hours-before]
  (t/interval (t/minus datetime (t/hours hours-before))
              datetime))


(defn- filter-rows-within-datetime-hours
  "Filter `rows` which are within the interval given by
  `datetime-right` (exclusive) and dating back up to `hours-before`
  hours (inclusive)."
  [rows datetime-right hours-before]
  (filter (comp
           (partial t/within? (interval-hours-before datetime-right
                                                     hours-before))
           :datetime)
          rows))


(defn- filter-rows-hours-before-row-entry
  "Filter `rows` which are within `hours-before` hours
  before (inclusive) the datetime of the `row-entry`.

  This is not really efficient as it goes through all rows again even
  though the rows are (or at least should be) sorted by datetime. But
  at least it's readable :)'"
  [rows row-entry hours-before]
  (filter-rows-within-datetime-hours rows (:datetime row-entry) hours-before))


;; -- Rows -> Entries ----------------------------------------------------------

(defn- reduce-categories
  "if the row has a value (e.g. step count from google fit), use
  that. if we already have an entry for this sort of event, add the
  values.

  if the row has no value, treat it as a `1`, e.g. going to the gym
  twice in 12 hours is counted as `2`."
  [rows]
  (reduce (fn [acc row]
            (let [{:keys [category event value]} row
                  ;; the key `:value` exist and may have value
                  ;; `nil`. we want to use a `1` instead. we can't use
                  ;; destructuring (`:or`) above because the key
                  ;; exists!
                  value (or value 1)]
              (if-not (contains? (get acc category) event)
                (assoc-in acc [category event] value)
                (update-in acc [category event] + value))))
          {}
          rows))

(defn reduce-preceding-events
  [rows row-entry hours-before]
  (-> rows
      (filter-rows-hours-before-row-entry row-entry hours-before)
      remove-measurements
      reduce-categories))


(defn filter-reduce-preceding-events
  [rows selected-category selected-event hours-before]
  (let [measurements (filter-measurements rows selected-category selected-event)]
    (for [measurement measurements]
      [measurement
       (reduce-preceding-events rows measurement hours-before)])))

;; -- Re-Processing ------------------------------------------------------------

(def logistic-mapping
  "Mapping of values to `1` and `-1`. Used when converting the
  measurements in preparation for a logistic regression."
  {0 -1
   1 -1
   2 -1
   3 -1
   4 1
   5 1})

(defn rescale-values
  "Apply a new scale to the values."
  [entries]
  ;; this is ugly for multiple reasons: 1) assuming that each entry is
  ;; a vector and therefore calling `update` with an integer, then
  ;; using `logistic-mapping` implicitly as a function as we actually
  ;; want to look up a value in a map (`get` would be more explicit)
  (map #(update % 0 update :value logistic-mapping) entries))

(defn round-predictions
  [predictions {:keys [logistic?]}]
  (let [round-fn (if logistic?
                   #(if (>= % 0) 1 -1)
                   #(Math/round %))]
    (map round-fn predictions)))


;; -- Vowpal-Wabbit Export -----------------------------------------------------

(defn- to-vw* [entries {:keys [counts?]
                        :or {counts? true}}]
  (for [entry entries]
    (let [target-val (-> entry first :value)
          reduced-categories (second entry)]
      (str target-val " "
           (reduce-kv (fn [s k reduced-events]
                        (str s "|" (name k) " "
                             (reduce-kv (fn [s event count]
                                          (if counts?
                                            (str s event ":" count " ")
                                            (str s event " ")))
                                        "" reduced-events)))
                      "" reduced-categories)))))


(defn to-vw [entries opts]
  (str/join "\n" (to-vw* entries opts)))


;; -- Performance Metrics ------------------------------------------------------

(defn confusion-matrix
  "Calculates the confusion matrix with the ground-truth values as
  \"origin\", for example:

  {1 {2 100}
   2 {2 50}}

  This would mean that there are two values in the dataset: 1 and
  2. For all values where 1 would have been correct, we predicted 2 a
  hundred times. For alle values where 2 would have been correct, we
  predicted 2 fifty times. We therefore trained a classifier which
  always predicts 2.. great!

  Some other things can be learned from the output. There are a total
  of 100 `1`s in the dataset (sum of vals of map of key 1) and a total
  of 50 `2`s."
  [entries predictions]
  {:pre [(= (count entries) (count predictions))]}
  (let [ground-truth-vals (map (comp :value first) entries)]
    ;; create a coll of tuples
    (->> (map vector ground-truth-vals predictions)
         (reduce (fn [acc [ground-truth-val prediction]]
                   (if (contains? (get acc ground-truth-val) prediction)
                     (update-in acc [ground-truth-val prediction] inc)
                     (assoc-in acc [ground-truth-val prediction] 1)))
                 {}))))

(defn accuracy
  "The accuracy is defined as the count of correct predictions divded by
  the count of all predictions."
  [entries predictions]
  {:pre [(= (count entries) (count predictions))]}
  (let [stats (reduce-kv
               (fn [m k v]
                 (-> m
                     (update :correct + (or (get v k) 0))
                     (update :total + (reduce + (vals v)))))
               {:correct 0
                :total   0}
               (confusion-matrix entries predictions))]
    (double (/ (:correct stats) (:total stats)))))

(defn balanced-accuracy
  "The balanced accuracy calculates the accuracy for each unique ground
  truth value and returns the average of them."
  [entries predictions]
  {:pre [(= (count entries) (count predictions))]}
  (let [;; returns a map with ground truth values as keys. each key is
        ;; a map with keys `:correct` and `:total` predictions for
        ;; this ground truth value.
        stats (reduce-kv
               (fn [m k v]
                 (-> m
                     (assoc-in [k :correct] (or (get v k) 0))
                     (assoc-in [k :total] (reduce + (vals v)))))
               {}
               (confusion-matrix entries predictions))
        ;; calculate the accuracy for each ground truth value by
        ;; simply dividing correct by total predictions
        accuracies (map (fn [[_ v]]
                          (/ (:correct v)
                             (:total v)))
                        stats)]
    ;; average all accuracies and divide them by the count of ground
    ;; truth values which is equal to the count of keys in `stats`
    (double (/ (reduce + accuracies) (count stats)))))

(defn split
  [fractions coll]
  (let [shuffled-coll   (shuffle coll)
        absolute-counts (->> (map (fn [[k v]] [k (Math/round (* v (count coll)))])
                                  fractions)
                             (into {}))]
    (loop [remaining-coll   shuffled-coll
           remaining-counts absolute-counts
           result           {}]
      (if (empty? remaining-counts)
        result
        (let [[description absolute-count] (first remaining-counts)]
          (recur
           (drop absolute-count remaining-coll)
           (dissoc remaining-counts description)
           (assoc result description (take absolute-count remaining-coll))))))))


;; -- Vowpal-Wabbit Execution --------------------------------------------------

(defn vw-train! [entries {:keys [loss-function]
                          :or   {loss-function :squared}}]
  (let [vw-input  (to-vw entries {})
        ;; model will be saved to `model` file
        cmd       ["vw"
                   "--passes" "20"
                   "--loss_function" (name loss-function)
                   "-f" "model"
                   "--cache_file" "cache"
                   ;; "--holdout_off"
                   "--kill_cache"]
        sh-args   (into cmd [:in vw-input
                             :dir "resources"])
        sh-result (apply sh sh-args)]
    (update sh-result :err str/split-lines)))


(defn vw-eval [entries]
  (let [vw-input          (to-vw entries {})
        cmd               ["vw"
                           "-f" "model"
                           "-p" "/dev/stdout"]
        sh-args           (into cmd [:in vw-input
                                     :dir "resources"])
        {:keys [exit out err]
         :as   sh-result} (apply sh sh-args)]
    (assert (= 0 exit)
            (ex-info "Non-zero exit code!" sh-result))
    (->> (str/split-lines out)
         (map #(Double/parseDouble %)))))


(defn vw-train-and-eval [entries {:keys [loss-function]
                                  :as   train-opts}]
  (let [{:keys [train test]} (split {:train 0.8 :test 0.2} entries)
        _                    (vw-train! train train-opts)
        process-predictions  (fn [predictions]
                               (round-predictions
                                predictions
                                {:logistic? (= :logistic loss-function)}))
        train-predictions    (process-predictions (vw-eval train))
        test-predictions     (process-predictions (vw-eval test))]
    {:train (balanced-accuracy train train-predictions)
     :test  (balanced-accuracy test test-predictions)}))


;; -- Analysis -----------------------------------------------------------------

(defn scatter-plot-from-tuples
  ([tuples] (scatter-plot-from-tuples tuples {}))
  ([tuples {:keys [title x-label y-label]}]
   (incanter.core/view
    (incanter.charts/scatter-plot (map first tuples) (map second tuples)
                                  :title title
                                  :x-label x-label
                                  :y-label y-label))))


(defn time-of-day [events category]
  (map (fn [event]
         (let [hour (+ (t/hour (:datetime event))
                       (/ (t/minute (:datetime event))
                          60))]
           [hour (:value event)]))
       (filter-measurements events category)))


(defn steps [entries]
  (map (fn [[measurement events-m]]
         [(get-in events-m [:google-fit "steps"] 0)
          (:value measurement)])
       entries))


(defn all-available-emfit-keys [entries]
  (->> entries
       (filter #(contains? (second %) :emfit-qs))
       first
       second
       :emfit-qs
       ;; no need to put this into a set as the keys of a map are
       ;; already unique
       keys))


(defn sleep-data [entries emfit-key]
  (map (fn [[measurement events-m]]
         [(get-in events-m [:emfit-qs emfit-key] 0)
          (:value measurement)])
       entries))

(defn sleep-duration [entries] (sleep-data entries "duration"))

(defn all-sleep-data [entries]
  (reduce (fn [acc emfit-key]
            (assoc acc emfit-key (sleep-data entries emfit-key)))
          {}
          (all-available-emfit-keys entries)))

(defn plot-all-sleep-data [entries]
  (doseq [[key tuples] (all-sleep-data entries)]
    (scatter-plot-from-tuples tuples {:title key})))

(defn correlate-all-sleep-data [entries]
  (reduce
   (fn [acc [key tuples]]
     (-> acc
         (assoc-in [key :pearson]
                   (incanter.stats/correlation (map first tuples)
                                               (map second tuples)))
         (assoc-in [key :spearman]
                   (incanter.stats/spearmans-rho (map first tuples)
                                                 (map second tuples)))))
   {}
   (all-sleep-data entries)))


(defn only-sleep-data [entries]
  (->> entries
       (filter (fn [[_ events-m]]
                 (contains? events-m :emfit-qs)))
       (map (fn [[measurement events-m]]
              ;; remove all other event except `:emfit-qs`
              [measurement (select-keys events-m [:emfit-qs])]))))


(defn stroop-vs-brain-fog [events stroop-type]
  (let [brain-fog-events (filter #(= :brain-fog (:category %))
                                 events)
        stroop-events (filter #(= :stroop (:category %))
                              events)]
    (for [event brain-fog-events]
      [(:value event)
       (->> stroop-events
            (filter #(and (= stroop-type (:event %))
                          (t/equal? (:datetime event) (:datetime %))))
            first
            :value)])))

(comment
  (read-csv "resources/correlate.events.2.csv")
  (split {:train 0.6 :test 0.2 :val 0.2} (range 10))
  (def csv-data (csv/read "resources/correlate.events.csv" {:skip-header? true}))
  (def csv (read-csv "resources/correlate.events.2.csv" {:skip-header? true}))
  (def rows (concat (preprocess-rows csv)
                    (google-fit/all-events)
                    (emfit-qs/all-events)))

  (def stroop-vs-fog (stroop-vs-brain-fog rows "offtime"))
  (scatter-plot-from-tuples stroop-vs-fog)
  (incanter.stats/correlation (map first stroop-vs-fog)
                              (map second stroop-vs-fog))
  (incanter.stats/spearmans-rho (map first stroop-vs-fog)
                                (map second stroop-vs-fog))


  (def entries (filter-reduce-preceding-events rows :stroop "ontime" 6))
  (sleep-data entries)
  (only-entries-with-sleep-data entries)
  (all-sleep-data entries)
  (scatter-plot-from-tuples (sleep-data entries "duration") {:title "Duration"})
  (incanter.stats/correlation (map first (sleep-data entries "duration"))
                              (map second (sleep-data entries "duration")))
  (counts (map (comp :value first) entries))
  (to-vw entries {})
  (rescale-values entries)
  (scatter-plot-from-tuples (sleep-data entries "duration-in-rem"))
  (all-available-emfit-keys entries)
  (plot-all-sleep-data entries)
  (correlate-all-sleep-data entries)
  (incanter.stats/linear-model (map second (sleep-data entries "sleep-score"))
                               (map first (sleep-data entries "sleep-score")))
  (only-sleep-data entries)

  (round-predictions (vw-eval (rescale-values entries)))
  (vw-train! entries {})

  (vw-train! (rescale-values entries) {:loss-function :logistic})
  (vw-eval entries)
  (counts (vw-eval (rescale-values entries)))
  (counts (map (comp :value first) (rescale-values entries)))
  (counts (round-predictions (vw-eval entries) {}))
  (counts (round-predictions (vw-eval entries) {:logistic? true}))
  (confusion-matrix entries (round-predictions (vw-eval entries) {}))
  (vw-train-and-eval entries {})
  (vw-train-and-eval (rescale-values entries) {:loss-function :logistic})

  (def predictions-rounded
    (round-predictions (vw-eval entries) {:logistic? true}))
  (confusion-matrix (rescale-values entries)
                    (round-predictions (vw-eval entries) {:logistic? true}))
  (confusion-matrix entries
                    (round-predictions (vw-eval entries) {}))
  (balanced-accuracy entries (round-predictions (vw-eval entries) {}))
  (accuracy (rescale-values entries) predictions-rounded)

  (counts predictions-rounded)
  (spit "resources/brain-fog-temp.txt" (to-vw (only-sleep-data entries) {}))
  (spit "resources/poop-temp.txt" (to-vw entries {}))
  (require 'incanter.datasets)
  (incanter.core/view (incanter.stats/sample-normal 10))
  (incanter.dataset/get-dataset :iris)
  (incanter.core/to-matrix (incanter.datasets/get-dataset :chwirut))

  (def time-of-day-vals (time-of-day rows :weight))
  (steps entries)
  (scatter-plot-from-tuples (steps entries))
  (incanter.core/view
   (incanter.charts/scatter-plot (map first time-of-day-vals)
                                 (map second time-of-day-vals)))
  (dotimes [_ 2]
    (incanter.core/view
     (incanter.charts/scatter-plot
      (incanter.core/sel (incanter.datasets/get-dataset :chwirut)
                         :cols 1)
      (incanter.core/sel (incanter.datasets/get-dataset :chwirut)
                         :cols 0)
      :legend true)))
  )
