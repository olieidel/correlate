(ns correlate.emfit-qs
  (:require clj-time.coerce
            [clj-time.core :as clj-time]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [correlate.csv :as csv]))


;; -- CSV Parsing --------------------------------------------------------------

(defn- parse-timestamp-str [x]
  ;; Strangely, the datetimes are still off by 1 hour. Maybe due to
  ;; daylight saving time or so?
  (letfn [(timestamp->datetime
            [timestamp]
            (clj-time/to-time-zone (clj-time.coerce/from-long (* 1000 timestamp))
                                   (clj-time/time-zone-for-offset 1)))]
    (-> (csv/read-string-safe x)
        timestamp->datetime)))


(defn- parse-seconds->hours [x]
  (letfn [(seconds->hours
            [seconds]
            (when seconds
              (/ seconds 3600)))]
    (-> (csv/read-string-safe x)
        seconds->hours)))


(def ^:private column-descriptions
  [{:key :id}
   {:key :device-id}
   {:key      :from
    :parse-fn parse-timestamp-str}
   {:key      :to
    :parse-fn parse-timestamp-str}
   {:key      :duration-in-bed
    :parse-fn parse-seconds->hours}
   {:key :avg-hr}
   {:key :avg-rr}
   {:key :avg-act}
   {:key :tossnturn-count}
   {:key :sleep-score}
   {:key      :duration-awake
    :parse-fn parse-seconds->hours}
   {:key      :duration-in-sleep
    :parse-fn parse-seconds->hours}
   {:key      :duration-in-rem
    :parse-fn parse-seconds->hours}
   {:key      :duration-in-light
    :parse-fn parse-seconds->hours}
   {:key      :duration-in-deep
    :parse-fn parse-seconds->hours}
   {:key      :duration-sleep-onset
    :parse-fn parse-seconds->hours}
   {:key :bedexit-count}
   {:key :from-gmt-offset}
   {:key :sleep-epoch-data}
   {:key :hr-rr-variation-data}
   {:key :awakenings}
   {:key      :bedexit-duration
    :parse-fn parse-seconds->hours}
   {:key      :duration
    :parse-fn parse-seconds->hours}
   {:key :hr-min}
   {:key :hr-max}
   {:key :rr-min}
   {:key :rr-max}
   {:key :hrv-rmssd-evening}
   {:key :user-utc-offset-minutes}
   {:key :resting-hr}
   {:key      :from-string
    :parse-fn identity}
   {:key :to-string
    :parse-fn identity}
   {:key :uid}
   {:key :object-id
    :parse-fn identity}
   {:key :hrv-score}
   {:key :hrv-lf}
   {:key :hrv-hf}
   {:key :hrv-time}])


(defn read-csv
  [csv-path]
  (csv/read-and-parse csv-path column-descriptions))


(defn- all-csv-paths
  [directory]
  (->> (file-seq (io/file directory))
       (filter (fn [file]
                 (and (.isFile file)
                      ;; there's probably a way more elegant way to do
                      ;; this with a glob / regex
                      (let [file-name (-> file .toPath .getFileName .toString)]
                        (and (str/ends-with? file-name ".csv")
                             (every? false?
                                     (map #(str/includes? file-name %)
                                          ["bedexits" "hrv" "sleepclasses" "vitals"
                                           "tossnturns"])))))))
       (map #(-> % .toPath .toString))))


(defn read-all-csvs
  [directory]
  (mapcat read-csv (all-csv-paths directory)))


;; -- Processing ---------------------------------------------------------------

(def ^:private interesting-row-keys
  [:duration-in-bed :avg-hr :bedexit-duration :hrv-hf :sleep-score
   :duration-in-light :resting-hr :rr-max :hrv-kf :tossnturn-count
   :avg-act :awakenings :duration :hr-min :hrv-rmssd-evening
   :bedexit-count :hr-max :duration-in-rem :avg-rr :duration-in-deep
   :duration-sleep-onset :duration-awake :hrv-score :duration-in-sleep
   :rr-min])


(defn- row->event [row-map]
  (let [datetime (:to row-map)]
    (for [[k v] (select-keys row-map interesting-row-keys)]
      {:datetime datetime
       :category :emfit-qs
       :event    (name k)
       :value    v})))


(defn- process-rows [row-maps]
  (mapcat row->event row-maps))


;; -- Public API ---------------------------------------------------------------

(defn all-events
  "Parse all relevant Emfit QS CSV files in `directory` and return a
  concatenated list of events."
  [directory]
  (process-rows (read-all-csvs directory)))
