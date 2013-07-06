(ns ^{:doc "Generic event emitter implementation heavily inspired by gen_event in Erlang/OTP"}
  clojurewerkz.eep.emitter
  (:require clojure.pprint
            [clojurewerkz.eep.reactor   :as mr]
            [clojurewerkz.eep.selectors :as ms :refer [$]]
            [clojurewerkz.eep.windows        :as ws]
            [clojurewerkz.eep.clocks         :as cl]
            [com.ifesdjeen.utils.circular-buffer :as cb])
  (:import [java.util.concurrent ConcurrentHashMap]))

(alter-var-root #'*out* (constantly *out*))

(defn pprint-to-str
  [& objs]
  (let [w (java.io.StringWriter.)]
    (clojure.pprint/pprint objs w)
    (.toString w)))

(def global-handler :entrypoint)

(def ^{:doc "Default thread pool size, calculated as # available processors + 1"}
  pool-size (-> (Runtime/getRuntime)
                .availableProcessors
                inc))

(def ^:dynamic *emitter*)

(defmacro with-emitter
  "Helper macro for binding current emitter"
  [emitter & body]
  `(binding [*emitter* ~body]
     ~@body
     *emitter*))

(defprotocol IHandler
  (run [_ args])
  (state [_]))

(defprotocol IEmitter
  (add-handler [_ event-type handler])
  (handler-registered? [_ t f])
  (delete-handler [_ t] "Removes the handler `f` from the current emitter, that's used for event
type `t`. ")
  (get-handler [_] [_ t] "Returns all currently registered Handlers for Emitter")
  (notify [_ type args] "Asynchronous (default) event dispatch function. All the Handlers (both
stateful and stateless). Pretty much direct routing.")
  (notify-some [_ type-checker args] "Asynchronous notification, with function that matches an event type.
Pretty much topic routing.")
  (! [_ type args] "Erlang-style alias for `notify`")
  (swap-handler [_ t new-f] "Replaces typed event handler with `new-f` event handler.")
  (stop [_] "Cancels all pending tasks, stops event emission.")
  (alive? [_] "Returns wether the current emitter is alive or no")
  (register-exception [_ t e]))

(defn- collect-garbage
  "As we may potentially accumulate rather large amount of futures, we have to garbage-collect them."
  [futures]
  (filter #(not (.isDone %)) futures))

(defn extract-data
  [payload]
  (get-in payload [:data]))

(defn- add-handler-intern
  [handlers event-type handler]
  (swap! handlers assoc event-type handler))

(defn- delete-handler-intern
  [handlers event-type]
  (swap! handlers dissoc event-type))

(deftype Emitter [handlers errors reactor]
  IEmitter
  (add-handler [this event-type handler]

    (when (nil? (get handler event-type))
      (add-handler-intern handlers event-type handler)
      (mr/on reactor ($ event-type) (fn [e]
                                      (run handler e)))))

  (delete-handler [this event-type]
    (when-let [old-handler (get-handler this event-type)]
      (.unregister (.getConsumerRegistry reactor) event-type)
      (swap! handlers dissoc event-type)
      old-handler))

  (swap-handler [this event-type f]
    (let [old (delete-handler this event-type)]
      (add-handler this event-type f)
      old))

  (notify [_ t args]
    (mr/notify reactor t args))

  (! [this t args]
    (notify this t args))

  (get-handler [_]
    @handlers)

  (get-handler [_ t]
    (t @handlers))

  (stop [_]
    (-> reactor
        (.getDispatcher)
        (.shutdown)))

  (alive? [_]
    (-> reactor
        (.getDispatcher)
        (.alive)))

  (register-exception [_ t e]
    (.put errors t e))

  (toString [_]
    (pprint-to-str "\n" (mapv #(.toString %) @handlers))))

(defn new-emitter
  "Creates a fresh Event Emitter with the default executor."
  [&{:keys [dispatcher-type] :or {:dispatcher-type :thread-pool}}]
  (let [reactor (mr/create :dispatcher-type dispatcher-type)]
    (Emitter. (atom {}) (ConcurrentHashMap.) reactor)))

;;
;; Operations
;;

(deftype Aggregator [emitter f state_]
  IHandler
  (run [_ payload]
    (swap! state_ f (extract-data payload)))

  (state [_]
    @state_)

  Object
  (toString [_]
    (pprint-to-str f @state_) ))

(deftype CommutativeAggregator [emitter f state_]
  IHandler
  (run [_ payload]
    (dosync
     (commute state_ f (extract-data payload))))

  (state [_]
    @state_)

  Object
  (toString [_]
    (str "Handler: " f ", state: " @state_) ))

(deftype Observer [emitter f]
  IHandler
  (run [_ payload]
    (f (extract-data payload)))

  (state [_]
    nil))

(deftype Rollup [emitter f redistribute-t]
  IHandler
  (run [_ payload]
    (f (extract-data payload)))

  (state [_]
    nil)

  Object
  (toString [_]
    (str f ", " redistribute-t)))

(deftype Filter [emitter filter-fn rebroadcast]
  IHandler
  (run [_ payload]
    (let [data (extract-data payload)]
      (when (filter-fn data)
        (notify emitter rebroadcast data))))

  (state [_] nil)

  Object
  (toString [_]
    (str filter-fn ", " rebroadcast)))

(deftype Multicast [emitter multicast-types]
  IHandler
  (run [_ payload]
    (doseq [t multicast-types]
      (notify emitter t (extract-data payload))))

  (state [_] nil)

  Object
  (toString [_]
    (clojure.string/join ", " multicast-types)))

(deftype Splitter [emitter split-fn]
  IHandler
  (run [_ payload]
    (let [data (extract-data payload)]
      (notify emitter (split-fn data) data)))

  (state [_] nil)

  Object
  (toString [_]
    (clojure.string/join ", " [split-fn])))

(deftype Transformer [emitter transform-fn rebroadcast]
  IHandler
  (run [_ payload]
    (notify emitter rebroadcast (transform-fn (extract-data payload))))

  (state [_] nil)

  Object
  (toString [_]
    (clojure.string/join ", " [transform-fn rebroadcast])))

(deftype Buffer [emitter buf]
  IHandler
  (run [_ payload]
    (swap! buf conj (extract-data payload)))

  (state [_] (cb/to-vec @buf)))

;;
;; Builder fns
;;

(defn deffilter
  "Defines a filter operation, that gets typed tuples, and rebroadcasts ones for which `filter-fn` returns true"
  ([t filter-fn rebroadcast]
     (deffilter *emitter* t filter-fn rebroadcast))
  ([emitter t filter-fn rebroadcast]
     (add-handler emitter t (Filter. emitter filter-fn rebroadcast))))

(defn deftransformer
  "Defines a transformer, that gets typed tuples, transforms them with `transform-fn` and rebroadcasts them."
  ([t transform-fn rebroadcast]
     (deftransformer *emitter* t transform-fn rebroadcast))
  ([emitter t transform-fn rebroadcast]
     (add-handler emitter t (Transformer. emitter transform-fn rebroadcast))))

(defn defaggregator
  "Defines an aggregator, that is initialized with `initial-state`, then gets typed tuples and aggregates state
   by applying `aggregate-fn` to current state and tuple."
  ([t aggregate-fn initial-state]
     (defaggregator *emitter* t aggregate-fn initial-state))
  ([emitter t aggregate-fn initial-state]
     (add-handler emitter t (Aggregator. emitter aggregate-fn (atom initial-state)))))

(defn defcaggregator
  "Defines a commutative aggregator, that is initialized with `initial-state`, then gets typed tuples and
   aggregates state by applying `aggregate-fn` to current state and tuple."
  ([t aggregate-fn initial-state]
     (defcaggregator *emitter* t aggregate-fn initial-state))
  ([emitter t aggregate-fn initial-state]
     (add-handler emitter t (CommutativeAggregator. emitter aggregate-fn (ref initial-state)))))

(defn defmulticast
  "Defines a multicast, that receives a typed tuple, and rebroadcasts them to several types of the given emitter."
  ([t m]
     (defmulticast *emitter* t m))
  ([emitter t m]
     (let [h (delete-handler emitter t)]
       (add-handler emitter t
                    (Multicast. emitter
                                (if (isa? Multicast (type h))
                                  (set (concat (.multicast-types h) m))
                                  (set m)))))))

(defn defsplitter
  ([t split-fn]
     (defsplitter *emitter* t split-fn))
  ([emitter t split-fn]
     (add-handler emitter t (Splitter. emitter split-fn))))

(defn defobserver
  "Defines an observer, that runs (potentially with side-effects) f for tuples of given type."
  ([t f]
     (defobserver *emitter* t f))
  ([emitter t f]
     (add-handler emitter t (Observer. emitter f))))

(defn defrollup
  "Rollup is a timed window, that accumulates entries until it times out, and emits them
   to the next processing part afterwards. Rollup resolution should not be less than 10 milliseconds."
  [emitter t period redistribute-t]
  (let [window (ws/timed-window-simple
                (cl/make-wall-clock period)
                10 identity
                #(notify emitter redistribute-t %))]
    (add-handler emitter t (Rollup. emitter window redistribute-t))))

(defn defbuffer
  "Defines a circular buffer with given `capacity`"
  [emitter t capacity]
  (add-handler emitter t (Buffer. emitter (atom (cb/circular-buffer capacity)))))

;;
;; Debug utils
;;

(defmacro carefully
  "Test macro, should only be used internally"
  [emitter handler-type & body]
  `(try
     ~@body
     (catch Exception e#
       (println "Exception occured while processing " ~handler-type ": " (.getMessage e#))
       (register-exception ~emitter ~handler-type e#))))

(defn wrap-carefully
  "Helper method to help with debugging of complex flows, when something is failing and you don't really see why"
  [emitter handler-type f]
  (fn [a b]
    (carefully emitter handler-type
               (f a b))))

(defn wrap-debug
  "Helper method to help with debugging of complex flows, when something is failing and you don't really see why"
  [emitter handler-type f]
  (fn [a b]
    (let [res (f a b)]
      (println (format "%s - %s: Input: [%s, %s], Output: %s"
                       (.getName (Thread/currentThread))
                       handler-type
                       a b
                       res))
      res)))

(defmacro build-topology
  "Builds aggregation topology from the given `hander-type` and handler builder."
  ([emitter a b]
     (concat (list (first b) emitter a) (rest b)))
  ([emitter a b & more]
      `(do
         (build-topology ~emitter ~a ~b)
         (build-topology ~emitter ~@more)
         ~emitter)))

;;
;;
;;

(defn keep-last
  "Aggregator helper function, always keeps only last value"
  [_ last]
  last)

(defn group-aggregate
  "Wrapper function for aggregators"
  [aggregate-fn tuples]
  (into {}
        (for [[k vals] (group-by first tuples)]
          [k (aggregate-fn (map second vals))])))
