(ns correlate.google-fit
  (:require [clojure.data.csv :as data.csv]
            [clojure.java.io :as io]
            [clj-time.core :as clj-time]
            clj-time.format
            [clojure.string :as str]
            [correlate.csv :as csv]))


(def ^:dynamic *google-fit-csv-path*
  "Directory where the Google Fit CSV files are."
  "resources/Takeout/Fit/Daily Aggregations")


;; -- CSV Parsing --------------------------------------------------------------

(defn- parse-datetime
  "Parse an ISO 8601 - formatted datetime string."
  [s]
  (-> (clj-time.format/parse (clj-time.format/formatter :date-time-parser)
                             s)
      (clj-time/to-time-zone (clj-time/time-zone-for-offset 1))))


(def ^:private row-keys
  [:start-time :end-time :calories-kcal :distance-m :low-latitude-deg
   :low-longitude-deg :high-latitude-deg :high-longitude-deg :average-speed-m-s
   :max-speed-m-s :min-speed-m-s :step-count :average-weight-kg :max-weight-kg
   :min-weight-kg :inactive-duration-ms :walking-duration-ms])


(defn- parse-int [x] (when-not (str/blank? x) (Integer/parseInt x)))
(defn- parse-double [x] (when-not (str/blank? x) (Double/parseDouble x)))
(defn- read-string-safe [x] (when-not (str/blank? x) (read-string x)))


(def ^:private row-parsers
  "A CSV row has multiple columns. Each column has a specific parsing
  function as defined in the map below."
  {:start-time           identity
   :end-time             identity})


(defn- preprocess-row [row]
  (reduce-kv (fn [m k v]
               (assoc m k ((get row-parsers k csv/read-string-safe) v)))
             {}
             (zipmap row-keys row)))


(defn- file-name-without-ending [file-path]
  (-> (str/split file-path #"/")
      last
      (str/split #"\.")
      first))


(defn- fix-date-strs [file-path row-map]
  (let [date-str (file-name-without-ending file-path)
        merge-str-fn #(str date-str "T" %)]
    (-> row-map
        (update :start-time merge-str-fn)
        (update :end-time merge-str-fn))))


(defn- parse-datetime-strs [row-map]
  (-> row-map
      (update :start-time parse-datetime)
      (update :end-time parse-datetime)))


(defn- read-csv
  [file-path]
  (->> (csv/read file-path)
       (map preprocess-row)
       (map (partial fix-date-strs file-path))
       (map parse-datetime-strs)))


(defn- all-csv-paths
  [directory]
  (->> (file-seq (io/file directory))
       (filter #(and (.isFile %)
                     ;; there's probably a way more elegant way to do
                     ;; this with a glob / regex
                     (let [file-name (-> % .toPath .getFileName .toString)]
                       (and (str/ends-with? file-name ".csv")
                            (not= file-name "Daily Summaries.csv")))))
       (map #(-> % .toPath .toString))))


(defn- read-all-csvs
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

(defn all-events []
  (process-rows (read-all-csvs *google-fit-csv-path*)))


(comment
  (parse-datetime "2018-09-03T00:15:00.000+01:00")
  (count (read-all-csvs "resources/Takeout/Fit/Daily Aggregations"))
  (def csv
    (read-csv "resources/Takeout/Fit/Daily Aggregations/2018-09-03.csv"))
  (def events
    (process-rows csv))
  (def all (all-events))
  (count all))
