(ns unlearn.demo.macro
  (:require [unlearn.virtual.macros :as m]))

;;;
;; examples

(defn whoami []
  (let [n (.getName (Thread/currentThread))]
    (println n)
    n))

(time
  (m/schedule [a (m/task (Thread/sleep 100) (+ 1 1))
               b (m/task (Thread/sleep 100) 2)
               c (m/race (Thread/sleep 10)
                         (Thread/sleep 10)
                         1)]
            [a b c]))

;; 100ms because a and b can be run independently

(defn log [steps]
  (dotimes [i steps]
    (Thread/sleep ^long (rand-int 100))
    (println (str (.getName (Thread/currentThread)) "Hello: " i)))
  steps)

(m/race (log 10)
      (log 9))

(m/parallel
  (fn [] (whoami) :first)
  :second
  (constantly 3)
  (m/task (whoami))
  (m/task nil)
  (do :do)
  (m/task (whoami) (+ 1 2)))
