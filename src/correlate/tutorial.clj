(ns correlate.tutorial
  (:require [correlate.core :as c]))

;; welcome and congratulations that you're getting into this stuff!

;; let's get started by loading some data

(def sheets-events
  (c/read-csv "resources/google-sheets-example.csv"))

;; check it out. it's a list of maps, each map is an "event". let's
;; look at the first event:

(first sheets-events)

;; => {:datetime #object[org.joda.time.DateTime 0x4c4b05f6 "2018-09-18T19:50:00.000+01:00"]
;;     :category :food,
;;     :event    "tortilla-chips"
;;     :value    nil}

;; so the `:datetime` is a datetime instance generate by `clj-time`,
;; `:category` is the second column of the csv file parsed to a
;; keyword, `:event` is the third column and `:value` the
;; fourth. pretty simple!

;; let's explore this data a bit. we're interested in the different
;; categories. how mmany entries to we have per category? for that, we
;; need a way to only list the categories first:

(map :category sheets-events)

;; cool! now count them. luckily, I did some plumbing for you:

(c/counts (map :category sheets-events))
;; => {:food 13, :stroop 8, :brain-fog 2}

;; pretty sweet. now we're only interested in the `:brain-fog`
;; measurements. for that, we need to define a time window. this
;; basically answers the question "what did I do in the past x hours
;; before my brain fog became bad (5)?"

(def reduced-sheets-events
  (c/filter-reduce-preceding-events sheets-events :brain-fog "brain-fog" 24))

;; we get a list of tuples. let's look at the first entry again to
;; understand it:

(first reduced-sheets-events)
;; => [{:datetime #object[org.joda.time.DateTime 0x6cc53ae3 "2018-09-19T08:30:00.000+01:00"],
;;      :category :brain-fog,
;;      :event "brain-fog",
;;      :value 3}
;;     {:food {"tortilla-chips" 1,
;;             "cheese" 1,
;;             "beans" 1,
;;             "salad" 1,
;;             "rice" 1}}]

;; so this is a vector with two items (a tuple). the first item is our
;; measurement we were interested in (brain fog). the second item is a
;; map of things which we did in the 24 hours before (we specified
;; `24` in the function call above). he things we did before are keyed
;; by category and event including the counts. for example, we had
;; tortilla chips once in the 24 hours before our brain fog was 5.

;; let's train a vowpal-wabbit model on this data! this gives us some
;; output but more importantly saves the model weights to
;; `resources/model`.
(c/vw-train! reduced-sheets-events {:loss-fn :squared})

;; let's run some predictions from our model!
(c/vw-eval reduced-sheets-events)
;; => (1.235473 1.60588)

;; what's our accuracy?
(c/accuracy reduced-sheets-events (c/vw-eval reduced-sheets-events))
;; => 0.0

;; well, that definitely leaves some room for improvement. remember,
;; the example dataset is very small so no reason for despair! :)

;; this will not work. but when we have more data for a useful
;; train/test split, we can use the function below which combines both
;; of what we did above and gives us the accuracy:
(c/vw-train-and-eval reduced-sheets-events {:loss-fn :squared})
