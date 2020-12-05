(ns unlearn.demo.macro
  (:require [unlearn.virtual.macros :as m])
  (:import (java.time Instant Duration)
           (java.util.concurrent ArrayBlockingQueue)))

;;;
;; examples

(defn whoami []
  (let [n (.getName (Thread/currentThread))]
    (println n)
    n))

(defn logsteps [steps]
  (dotimes [i steps]
    (Thread/sleep ^long (rand-int 100))
    (print (str (.getName (Thread/currentThread)) "Hello: " i "\n")))
  steps)

(defn ^Instant deadline-in [secs]
   (.plus (Instant/now) (Duration/ofSeconds secs)))

;; a task is a fn body
;; that will get the result memoized
(let [task (m/task (println 1))]
  (task)
  (task))

;; tasks are not realized values!
;; they are basically a thunk
(let [task1 (m/task (println 1))
      task2 (m/task (+ 1 task1))])
  ;(task2));; error

;; task bodies work as closures
;; capturing enclosing values
(let [v    1
      task (m/task + 1 v)]
  (task))

;;;;
;; tasks can be "scheduled"

;; create 3 tasks a b c
;; they are independent, so total time should be just a bit above max sleep time (100ms)
(time
  (m/sequence [a (m/task (Thread/sleep 100) (+ 1 1))
               b (m/task (Thread/sleep 100) 2)
               c (m/race (Thread/sleep 10)
                         (Thread/sleep 10)
                         a)]
              [a b c])) ;; ~100ms

;; if there is a dependency it works too
;; so the result of the b task will depend on a completion
(time
  (m/sequence [a (m/task (println "start a") (Thread/sleep 100) 2)
               b (m/task (println "start b") (Thread/sleep 100) (+ 1 a))]
              [a b])) ;; ~200ms

;; race between these two fn calls
;; sometimes result is 10, sometimes 9
(m/race (logsteps 10)
        (logsteps 9))

;; parallel runs all tasks independently
;; a task can be something that implements clojure.core.Fn (NOT IFn)
;; otherwise it will resolve to itself
(m/parallel
  (fn [] (whoami) :first)
  :second
  (constantly 3)
  (m/task (whoami))
  (m/task nil)
  :a-value
  (m/task (whoami) (+ 1 2)))

;; all macros accept a custom executor or a deadline
;; structured concurrency ;)
(time
  (m/parallel ;; creates an executor for all m/ calls
    ;1
    (m/task (Thread/sleep 100) :100ms)
    ;2
    (m/race
      (do (Thread/sleep 300) :300ms)
      (do (Thread/sleep 400) :400ms))
    ;3
    (try
      (m/single :deadline (deadline-in 1)
        (do (Thread/sleep 1500)
            :try-ok))
      (catch InterruptedException _e :try-failed))))

