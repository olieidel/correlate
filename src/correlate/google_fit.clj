(ns correlate.google-fit
  (:require [clj-time.core :as clj-time]
            clj-time.format
            [clojure.java.io :as io]
            [clojure.string :as str]
            [correlate.csv :as csv]))

;; -- CSV Parsing --------------------------------------------------------------

(defn- parse-datetime
  "Parse an ISO 8601 - formatted datetime string."
  [s]
  (-> (clj-time.format/parse (clj-time.format/formatter :date-time-parser)
                             s)
      (clj-time/to-time-zone (clj-time/time-zone-for-offset 1))))


(defn- file-name-without-ending [file-path]
  (-> (str/split file-path #"/")
      last
      (str/split #"\.")
      first))


(defn- fix-date-str [file-path time-str]
  (str (file-name-without-ending file-path)
       "T" time-str))


(defn- parse-time [file-path time-str]
  (-> (fix-date-str file-path time-str)
      parse-datetime))


(defn- column-descriptions
  "We need the file path for parsing the time strings as they don't
  include the dates, therefore we need to generate the
  `column-descriptions` map for each CSV file we're parsing."
  [file-path]
  [{:key      :start-time
    :parse-fn (partial parse-time file-path)}
   {:key      :end-time
    :parse-fn (partial parse-time file-path)}
   {:key :calories-kcal}
   {:key :distance-m}
   {:key :low-latitude-deg}
   {:key :low-longitude-deg}
   {:key :high-latitude-deg}
   {:key :high-longitude-deg}
   {:key :average-speed-m-s}
   {:key :max-speed-m-s}
   {:key :min-speed-m-s}
   {:key :step-count}
   {:key :average-weight-kg}
   {:key :max-weight-kg}
   {:key :min-weight-kg}
   {:key :inactive-duration-ms}
   {:key :walking-duration-ms}])


(defn read-csv [csv-path]
  ;; Beware! Older Google Fit entries are missing the last two
  ;; fields (`inactive-duration-ms` and `:walking-duration-ms`)!
  (csv/read-and-parse csv-path (column-descriptions csv-path)))


(defn- all-csv-paths [directory]
  (->> (file-seq (io/file directory))
       (filter #(and (.isFile %)
                     ;; there's probably a way more elegant way to do
                     ;; this with a glob / regex
                     (let [file-name (-> % .toPath .getFileName .toString)]
                       (and (str/ends-with? file-name ".csv")
                            (not= file-name "Daily Summaries.csv")))))
       (map #(-> % .toPath .toString))))


(defn read-all-csvs
  "Read all Google Fit CSVs of a specified `directory` and parse
  them. Concatenate the results into one gigantic list, sorted by
  date."
  [directory]
  (mapcat read-csv (all-csv-paths directory)))


;; -- Processing ---------------------------------------------------------------

(defn- filter-step-counts [row-maps]
  (filter #(some? (:step-count %)) row-maps))


(defn- row->event
  "A crazy simplification. Only take the end time and step count."
  [row-map]
  {:datetime (:end-time row-map)
   :category :google-fit
   :event    "steps"
   :value    (:step-count row-map)})


(defn- process-rows [row-maps]
  (->> (filter-step-counts row-maps)
       (map row->event)))


;; -- Public API ---------------------------------------------------------------

(defn all-events [directory]
  (process-rows (read-all-csvs directory)))
