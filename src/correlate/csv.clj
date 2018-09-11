(ns correlate.csv
  (:require [clojure.data.csv :as data.csv]
            [clojure.java.io :as java.io]
            [clojure.string :as str]))


(defn read
  "Read a comma-separated values (csv) file at `file-path`. As second
  argument, a map of options is taken. If `:skip-header?` is `true`,
  the first row is skipped.


  Returns a list of rows. Each row is a vector of strings where each
  string is the content of a cell in the spreadsheet."
  ([file-path] (read file-path {}))
  ([file-path {:keys [skip-header?]
               :or {skip-header? true}}]
   (cond-> (with-open [reader (java.io/reader file-path)]
             (doall (data.csv/read-csv reader)))
     skip-header? rest)))


(defn read-string-safe
  "Call `read-string` on `x`, return `nil` if `x` is an empty string."
  [x]
  (when-not (str/blank? x) (read-string x)))


;; -- Row Processing -----------------------------------------------------------

(defn parse-row
  "Parse a single CSV row. `row-entries` is a vector of strings (a line
  of the CSV file), `column-descriptions` is a vector of maps where
  each map describes the respective (nth) entry in `row-entries`. A
  row description is of the form:

  {:key      :some-key-to-name-this-entry
   :parse-fn identity}

  Where `:key` is a (user-defined) key which describes this value and
  should be the key in the map which is returned and `:parse-fn` is
  the function to run over the value. If `:parse-fn` is not provided,
  it falls back to `read-string-safe`.

  Note: If the counts of `column-descriptions` and `row-entries`
  differ, the entries of the one with the larger count will be
  ignored (at the end of the coll)."
  [column-descriptions row-entries]
  (->> (map (fn [row-entry {:keys [key parse-fn]
                            :or   {parse-fn read-string-safe}}]
              [key (parse-fn row-entry)])
            row-entries column-descriptions)
       (into {})))


(defn parse-rows
  "Parses multiple rows. See the documentation of `process-row` for
  how this works."
  [column-descriptions rows]
  (map (partial parse-row column-descriptions) rows))


;; -- Convenience --------------------------------------------------------------

(defn read-and-parse [csv-path column-descriptions]
  (->> (read csv-path)
       (parse-rows column-descriptions)))


(comment
  (process-rows [{:key :first :parse-fn read-string-safe}
                 {:key :second :parse-fn read-string-safe}
                 {:key :n :parse-fn read-string-safe}]
                [["foozoo" "baza" "10"]
                 ["foo" "bar" "3"]]))
