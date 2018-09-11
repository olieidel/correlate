(ns correlate.clojutre-talk
  (:require [correlate.core :as c]
            [correlate.emfit-qs :as emfit-qs]
            [correlate.google-fit :as google-fit]))


;; -- Google Fit ---------------------------------------------------------------

(def google-fit-directory
  "Directory of the Google Fit CSV files."
  "resources/Takeout/Fit/Daily Aggregations")


(google-fit/all-events google-fit-directory)


;; -- Emfit QS -----------------------------------------------------------------

(def emfit-qs-directory
  "Directory of the Emfit QS CSV files."
  "resources/00244B-ed-2018-07-01--2018-09-09-1414c28a")


(emfit-qs/all-events emfit-qs-directory)
