(ns unlearn.demo.macro
  (:require [unlearn.virtual.macros :as m]
            [unlearn.virtual.executor :as e])
  (:import (java.time Instant Duration)
           (java.util.concurrent ExecutorService Executors)
           (clojure.lang IDeref)))

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
  (m/schedule [a (m/task (Thread/sleep 100) (+ 1 1))
               b (m/task (Thread/sleep 100) 2)
               c (m/race (Thread/sleep 10)
                         (Thread/sleep 10)
                         1)]
              [a b c]))

;; if there is a dependency it works too
;; so the result of the b task will depend on a completion
(time
  (m/schedule [a (m/task (println "start a") (Thread/sleep 100) 2)
               b (m/task (println "start b") (Thread/sleep 100) (+ 1 a))]
              [a b]))

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
  (do :do)
  (m/task (whoami) (+ 1 2)))

;; all macros accept a custom executor and a deadline
;; structured concurrency ;)
(time
  (m/parallel :deadline (deadline-in 1) ;; creates an executor for all m/ calls
    ;1
    (m/task (Thread/sleep 100) :100ms)
    ;2
    (m/race
      (do (Thread/sleep 300) :300ms)
      (do (Thread/sleep 400) :400ms))
    ;3
    (try
      (m/task
        (do (Thread/sleep 1500)
            :try-ok))
      (catch InterruptedException _e :try-failed))))


;; https://github.com/flatland/useful/blob/5de8a2ff32d351dcc931d0d10cdd4d67797bdc42/src/flatland/useful/utils.clj#L201
(defn thread-local*
  "Non-macro version of thread-local - see documentation for same."
  [init]
  (let [generator (proxy [ThreadLocal] []
                    (initialValue [] (init)))]
    (reify IDeref
      (deref [this]
        (.get generator)))))

(defmacro thread-local
  [& body]
  `(thread-local* (fn [] ~@body)))

(def current-executor (thread-local (atom nil)))

(defn h [] (println "HASH:" (.hashCode @@current-executor)))

(let [ve (unlearn.virtual.executor/executor)]
  (reset! @current-executor ve)
  (h)
  (with-open [ex @@current-executor]
    (h)
    (.submit ex
             (cast Callable
                   (fn []
                     (let [ve2 (unlearn.virtual.executor/executor)]
                       (reset! @current-executor ve2)
                       (with-open [ex2 @@current-executor]
                         (h))))))))
