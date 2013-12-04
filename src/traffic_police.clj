(ns traffic-police
  (:require clout.core)
  (:import clojure.lang.IPersistentMap))

(defprotocol TrafficPoliceRequest
  (get-request-method [req])
  (get-request-path [req])
  (assoc-route-params [req route-params])
  (get-method-not-allowed-response [req]))

(extend-protocol TrafficPoliceRequest
  IPersistentMap
  (get-request-method [req] (:request-method req))
  (get-request-path [req] (or (:uri req) (:path-info req))) ;; TODO: Figure out why we need path-info.
  (assoc-route-params [req route-params] (assoc req :route-params route-params))
  (get-method-not-allowed-response [req] {:status 405}))

(defn- run-preconditions
  [preconditions req]
  (if (not (nil? req))
    (if (empty? preconditions)
      req
      (recur (rest preconditions) ((first preconditions) req)))))

(defn- flatten-resource
  [[parent-path parent-preconditions] [path precondition handlers & children]]
  (let [resource [(str parent-path path)
                  (conj parent-preconditions precondition)
                  handlers]]
    (concat
     [resource]
     (mapcat
      #(flatten-resource resource %)
      children))))

(defn flatten-resources
  [resources]
  (mapcat
   (fn [[path precondition handlers & child-resources]]
     (let [root-resource [path [precondition] handlers]]
       (concat
        [root-resource]
        (mapcat
         #(flatten-resource root-resource %)
         child-resources))))
   resources))

(defn identity-negotiator
  [handler req]
  (handler req))

(defn default-negotiator
  [negotiators handler req]
  (((apply comp (reverse negotiators)) handler) req))

(defn handler
  ([r] (handler identity-negotiator r))
  ([negotiator r]
     (let [routes (map
                   (fn [[route-path route-preconditions route-handlers]]
                     (let [route (clout.core/route-compile route-path)]
                       (fn [req]
                         (when-let [route-match (clout.core/route-matches route {:path-info (get-request-path req)})]
                           (if-let [handler (get route-handlers (get-request-method req))]
                             (negotiator
                              (fn [req]
                                (when-let [processed-req (run-preconditions route-preconditions req)]
                                  (handler processed-req)))
                              (assoc-route-params req route-match))
                             (get-method-not-allowed-response req))))))
                   (flatten-resources r))]
       (fn [req]
         (some #(% req) routes)))))

(defmacro negotiate
  [& negotiators]
  `(fn [handler# req#]
     (~default-negotiator [~@negotiators] handler# req#)))

(defn chained-handlers
  [& handlers]
  (fn [req]
    (some #(% req) handlers)))

(defmacro r
  "The only purpose of this macro is to construct a vector identical
   to its arguments. The `r` function call makes the nested structure
   look better. You're free to just create the nested vector directiy
   without using this macro."
  [path precondition handlers & children]
  `[~path ~precondition ~handlers ~@children])
