(ns unlearn.virtual.macros
  "Highly experimental namespace for running code in threads.
  Use at your own peril.
  All thread executing fns accept either `:deadline` or `:executor` opts."
  (:refer-clojure :exclude [sequence])
  (:require [clojure.set :as set]
            [unlearn.virtual.executor :as executor]
            [riddley.walk :as walk]
            [riddley.compiler :as compiler])
  (:import (java.util.concurrent ExecutorService CompletableFuture)
           (clojure.lang Fn IDeref)))

;;;;
;; current executor
;; defines a threadlocal executor that will be passed down onto new threads
;; unless a new binding is set
;;;;

;; https://github.com/flatland/useful/blob/5de8a2ff32d351dcc931d0d10cdd4d67797bdc42/src/flatland/useful/utils.clj#L201
(defn- thread-local* [init]
  (let [generator (proxy [ThreadLocal] []
                    (initialValue [] (init)))]
    (reify IDeref
      (deref [this]
        (.get generator)))))

(defmacro ^:private thread-local [& body]
  `(thread-local* (fn [] ~@body)))

(def ^ExecutorService current-thread-executor (thread-local (atom nil)))

(defn set-local-executor [ex]
  (reset! @current-thread-executor ex))

(defn- decide-executor [{:keys [executor deadline] :as opts}]
  (cond (and executor deadline)
        (throw (IllegalArgumentException. "Cannot have both :executor and :deadline"))
        (some? executor) executor
        :else `(executor/executor ~opts)))

(defmacro -with-threadlocal-executor
  "Wraps body with the current executor or a new one, depending on supplied opts.
  Binds the current executor to `binding`"
  [[binding ex-opts] & body]
  (let [new-executor? (contains? ex-opts :deadline)]
    (if new-executor?
      `(with-open [~binding ~(decide-executor ex-opts)]
         (set-local-executor ~binding)
         ~@body)
      `(let [~binding ~(decide-executor ex-opts)]
         (set-local-executor ~binding)
         ~@body))))


(defn ^CompletableFuture submit
  "Reflection-friendly executor submit"
  [executor callable]
  (.submitTask ^ExecutorService executor ^Callable callable))


(defn ^CompletableFuture submit-many
  "Reflection-friendly executor submit"
  [executor callable]
  (.submitTasks ^ExecutorService executor ^Callable callable))


;;;;
;; public stuff

(defprotocol ITask
  (run-task [this]))

(extend-protocol ITask
  ;; sets, maps and keywords implement ifn? which means they would be
  ;; treated as tasks but they always need an argument
  Fn (run-task [f] (f))
  Object (run-task [f] f)
  nil (run-task [_] nil))

(defmacro task
  "Creates a task out of a body"
  [& body]
  `(fn [] (do ~@body)))

;;;;
;; macro helpers

(defn- tasks-of
  "Helper fn for `-with-threadlocal-executor` macro"
  [bindings]
  (mapv (fn [binding] `(fn [] (run-task ~binding)))
        bindings))

(defn- split-tasks-opts
  ([body]
   (split-tasks-opts body {}))
  ([body curr-opts]
   (let [deadline? (= :deadline (first body))
         deadline  (when deadline? (nth body 1))
         executor? (= :executor (first body))
         executor  (when executor? (nth body 1))
         ex-opts   (cond-> curr-opts
                           deadline? (merge {:deadline deadline})
                           executor? (merge {:executor executor}))
         tasks     (if (or deadline? executor?)
                     (rest (rest body))
                     body)]
     (if (not (or deadline? executor?))
       [tasks (merge curr-opts ex-opts)]
       (split-tasks-opts tasks ex-opts)))))

;; (split-tasks-opts '(:executor 2  :deadline 1 (+ 1 1)))
;; [((+ 1 1)) {:executor 2, :deadline 1}]

(defmacro parallel
  "Runs each form within its own virtual thread."
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)]
     `(let [tasks# ~(tasks-of tasks)]
        (-with-threadlocal-executor [e# ~ex-opts]
          (->> (submit-many e# tasks#)
               (mapv #(.get %))))))))

(defmacro race
  "Runs each form within its own virtual thread, returning the first to finish"
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)]
     `(let [tasks# ~(tasks-of tasks)]
        (-with-threadlocal-executor [e# ~ex-opts]
          (.invokeAny e# tasks#))))))

(defmacro single
  "Runs a form within its own virtual thread, returning the first to finish"
  ([& body]
   (let [[tasks ex-opts] (split-tasks-opts body)]
     `(-with-threadlocal-executor [e# ~ex-opts]
        (.get (submit e# (fn [] ~@tasks)))))))

;; shamelessly copied from manifold.deferred/back-references
(defn- back-references [marker form]
  (let [syms (atom #{})]
    (walk/walk-exprs
      symbol?
      (fn [s]
        (when (some-> (compiler/locals) (find s) key meta (get marker))
          (swap! syms conj s)))
      form)
    @syms))

;; shamelessly copied from manifold.deferred/expand-let-flow
(defn- expand-let [bindings [body] ex-opts]
  (let [[_ bindings & body] (walk/macroexpand-all `(let ~bindings ~body))
        locals         (keys (compiler/locals))
        vars           (->> bindings (partition 2) (map first))
        custom-ex      (gensym "custom-executor")
        marker         (gensym)
        vars'          (->> vars (concat locals) (map #(vary-meta % assoc marker true)))
        gensyms        (repeatedly (count vars') gensym)
        gensym->var    (zipmap gensyms vars')
        vals'          (->> bindings (partition 2) (map second) (concat locals))
        gensym->deps   (zipmap
                         gensyms
                         (->> (count vars')
                              range
                              (map
                                (fn [n]
                                  `(let [~@(interleave (take n vars') (repeat nil))
                                         ~(nth vars' n) ~(nth vals' n)])))
                              (map
                                (fn [n form]
                                  (map
                                    (zipmap vars' (take n gensyms))
                                    (back-references marker form)))
                                (range))))
        binding-dep?   (->> gensym->deps vals (apply concat) set)

        body-dep?      (->> `(let [~@(interleave
                                       vars'
                                       (repeat nil))]
                               ~@body)
                            (back-references marker)
                            (map (zipmap vars' gensyms))
                            (concat (drop (count vars) gensyms))
                            set)
        dep?           (set/union binding-dep? body-dep?)]
    `(-with-threadlocal-executor [~custom-ex ~ex-opts]
       (let [~@(mapcat
                 (fn [_n _var val gensym]
                   ;; use delay to defer execution up until the very last step
                   (let [deps (gensym->deps gensym)]
                     (if (empty? deps)
                       (when (dep? gensym)
                         [gensym `(submit ~custom-ex (fn [] (run-task ~val)))])
                       [gensym
                        `(submit ~custom-ex ;; :executor ~custom-ex
                                 (fn []
                                   (apply (fn [[~@(map gensym->var deps)]]
                                            ;; TODO: because val is a task
                                            (run-task ~val))
                                          [[~@(for [d deps] `@~d)]])))])))
                 (range)
                 vars'
                 vals'
                 gensyms)]

         ;; destructure from an array of resolved vars
         ;; to run the body
         (let [[~@(map gensym->var body-dep?)] [~@(for [d body-dep?] `@~d)]]
           ~@body)))))

(defmacro sequence
  "Sequences a set of let-bindings and executes the bindings."
  [& body]
  (let [[tasks ex-opts] (split-tasks-opts body)
        [bindings & bbody] tasks]
    (expand-let bindings bbody ex-opts)))
