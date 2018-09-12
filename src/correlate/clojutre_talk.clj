(ns correlate.clojutre-talk
  (:require [correlate.core :as c]
            [correlate.csv :as csv]
            [correlate.emfit-qs :as emfit-qs]
            [correlate.google-fit :as google-fit]))


;; -- Food via Google Sheets, first try ----------------------------------------

(def google-sheets-file
  "CSV file, second try"
  "resources/correlate.events.csv")

;; read some csv rows
(csv/read google-sheets-file)

;; parse
(def events (c/read-csv google-sheets-file))














;; -- Google Fit ---------------------------------------------------------------

(def google-fit-directory
  "Directory of the Google Fit CSV files."
  "resources/Takeout/Fit/Daily Aggregations")


(def google-fit-events
  (google-fit/all-events google-fit-directory))

;; parse
(def events-with-google-fit (concat events
                                    google-fit-events))

;; reduce
(def entries-with-google-fit
  (c/filter-reduce-preceding-events
   events-with-google-fit :brain-fog "brain-fog" 6))


(c/vw-train-and-eval entries-with-google-fit {:loss-fn :quadratic
                                              :eval-fn c/accuracy})

;; convert it to vowpal-wabbit format
(c/to-vw entries)

;; train a vowpal-wabbit model
(c/vw-train! entries)

;; evaluate on the training set (does it work at all?)
(c/vw-eval entries)

;; woops! we should be rounding, right?
(c/accuracy entries (c/round-predictions (c/vw-eval entries)))

;; we should actually be using a test set
(c/vw-train-and-eval entries {:loss-fn :quadratic
                              :eval-fn c/accuracy})


;; -- Emfit QS -----------------------------------------------------------------

(def emfit-qs-directory
  "Directory of the Emfit QS CSV files."
  "resources/00244B-ed-2018-07-01--2018-09-09-1414c28a")


(def emfit-qs-data
  (emfit-qs/all-events emfit-qs-directory))


;; -- v2 Google Sheets with Stroop Test ----------------------------------------

(def google-sheets-v2-file
  "CSV file, second try"
  "resources/correlate.events.2.csv")

;; read step count from google fit csv files
(def google-sheets-events (c/read-csv google-sheets-v2-file))

;; whip everything into one list
(def all-rows (concat google-sheets-events
                      google-fit-steps
                      emfit-qs-data))

;; reduce it to entries
(def entries (c/filter-reduce-preceding-events all-rows :stroop "offtime" 6))

;; plot stuff
(c/scatter-plot-from-tuples
 (c/sleep-data entries "duration-awake")
 {:x-label "Duration awake (h)"
  :y-label "Stroop Test Duration (s)"})
















;; -- Extras -------------------------------------------------------------------

(c/correlate-all-sleep-data entries)

;; -- Logistic Regression ------------------------------------------------------

;; let's try logistic regression
(comment c/logistic-mapping)
(def rescaled-entries (c/rescale-values entries))
(c/vw-train! rescaled-entries {:loss-fn :logistic})
;; what's our training accuracy?
(def predictions (c/round-predictions (c/vw-eval rescaled-entries)
                                      {:logistic? true}))

(c/accuracy rescaled-entries predictions)

(c/counts (map (comp :value first) rescaled-entries))
(c/counts predictions)

(c/balanced-accuracy rescaled-entries predictions)

(c/vw-train-and-eval rescaled-entries {:loss-fn :logistic
                                       :eval-fn c/balanced-accuracy})

(c/confusion-matrix rescaled-entries predictions)

(spit "resources/brain-fog-logistic-temp.txt" (c/to-vw rescaled-entries))
