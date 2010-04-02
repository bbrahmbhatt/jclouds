(ns org.jclouds.compute
  "A clojure binding to the jclouds ComputeService.

Current supported services are:
   [ec2, rimuhosting, terremark, vcloud, hostingdotcom]

Here's an example of getting some compute configuration from rackspace:

  (use 'org.jclouds.compute)
  (use 'clojure.contrib.pprint)

  (def user \"username\")
  (def password \"password\")
  (def compute-name \"cloudservers\")

  (def compute (compute-context compute-name user password))

  (pprint (locations compute))
  (pprint (images compute))
  (pprint (nodes compute))
  (pprint (sizes compute))

See http://code.google.com/p/jclouds for details."
  (:use org.jclouds.core
        clojure.contrib.duck-streams
        clojure.contrib.logging
        [clojure.contrib.str-utils2 :only [capitalize lower-case map-str]]
        [clojure.contrib.java-utils :only [wall-hack-field]])
  (:import java.io.File
           [org.jclouds.domain Location]
           [org.jclouds.compute
            ComputeService ComputeServiceContext ComputeServiceContextFactory]
           [org.jclouds.compute.domain
            Template TemplateBuilder ComputeMetadata NodeMetadata Size OsFamily
            Image Architecture]
           org.jclouds.compute.options.TemplateOptions
           [com.google.common.collect ImmutableSet]))

(defn compute-service
  "Create a logged in context."
  ([#^String service #^String account #^String key & options]
     (.. (ComputeServiceContextFactory.)
         (createContext
          service account key
          (apply modules (filter #(not (#{:sync :async} %)) options)))
         (getComputeService))))

(defn compute-context
  "Returns a compute context from a compute service."
  [compute]
  (.getContext compute))

(defn compute-service?
  [object]
  (instance? ComputeService object))

(defn compute-context?
  [object]
  (instance? ComputeServiceContext object))

(defn as-compute-service
  "Tries hard to produce a compute service from its input arguments"
  [& args]
  (cond
   (compute-service? (first args)) (first args)
   (compute-context? (first args)) (.getComputeService (first args))
   :else (apply compute-service args)))

(def *compute*)

(defmacro with-compute-service
  "Specify the default compute service"
  [[& compute-or-args] & body]
  `(binding [*compute* (as-compute-service ~@compute-or-args)]
     ~@body))

(defn locations
  "Retrieve the available compute locations for the compute context."
  ([] (locations *compute*))
  ([#^ComputeService compute]
     (seq-from-immutable-set (.getLocations compute))))

(defn nodes
  "Retrieve the existing nodes for the compute context."
  ([] (nodes *compute*))
  ([compute-or-tag]
     (if (compute-service? compute-or-tag)
       (seq-from-immutable-set (.getNodes compute-or-tag))
       (nodes compute-or-tag *compute*)))
  ([#^String tag #^ComputeService compute]
     (seq-from-immutable-set (.getNodesWithTag compute tag))))

(defn images
  "Retrieve the available images for the compute context."
  ([] (images *compute*))
  ([#^ComputeService compute]
     (seq-from-immutable-set (.getImages compute))))

(defn sizes
  "Retrieve the available node sizes for the compute context."
  ([] (sizes *compute*))
  ([#^ComputeService compute]
     (seq-from-immutable-set (.getSizes compute))))

(defn default-template
  ([] (default-template *compute*))
  ([#^ComputeService compute]
     (.. compute (templateBuilder)
         (osFamily OsFamily/UBUNTU)
         smallest
         (options
          (org.jclouds.compute.options.TemplateOptions$Builder/authorizePublicKey
           (slurp (str (. System getProperty "user.home") "/.ssh/id_rsa.pub"))))
         build)))

(defn run-nodes
  "Create the specified number of nodes using the default or specified
   template."
  ([tag count]
     (run-nodes tag count (default-template *compute*) *compute*))
  ([tag count compute-or-template]
     (if (compute-service? compute-or-template)
       (run-nodes
        tag count (default-template compute-or-template) compute-or-template)
       (run-nodes tag count compute-or-template *compute*)))
  ([tag count template #^ComputeService compute]
     (seq-from-immutable-set
      (.runNodesWithTag compute tag count template))))

(defn run-node
  "Create a node using the default or specified template."
  ([tag]
     (run-nodes tag 1 (default-template *compute*) *compute*))
  ([tag compute-or-template]
     (if (compute-service? compute-or-template)
       (run-nodes tag 1 (default-template compute-or-template) compute-or-template)
       (run-nodes tag 1 compute-or-template *compute*)))
  ([tag template compute]
     (run-nodes tag 1 template compute)))

(defn #^NodeMetadata node-details
  "Retrieve the node metadata."
  ([node] (node-details node *compute*))
  ([node #^ComputeService compute]
     (.getNodeMetadata compute node )))

(defn reboot-nodes
  "Reboot all the nodes with the given tag."
  ([tag] (reboot-nodes tag *compute*))
  ([#^String tag #^ComputeService compute]
     (.rebootNodesWithTag compute tag )))

(defn reboot-node
  "Reboot a given node."
  ([node] (reboot-node node *compute*))
  ([#^ComputeMetadata node #^ComputeService compute]
     (.rebootNode compute node )))

(defn destroy-nodes
  "Destroy all the nodes with the given tag."
  ([tag] (destroy-nodes tag *compute*))
  ([#^String tag #^ComputeService compute]
     (.destroyNodesWithTag compute tag )))

(defn destroy-node
  "Destroy a given node."
  ([node] (destroy-node node *compute*))
  ([#^ComputeMetadata node #^ComputeServiceContext compute]
     (.destroyNode compute node)))

(defmacro state-predicate [node state]
  `(= (.getState ~node)
      (. org.jclouds.compute.domain.NodeState ~state)))

(defn pending?
  "Predicate for the node being in transition"
  [#^NodeMetadata node]
  (state-predicate node PENDING))

(defn running?
  "Predicate for the node being available for requests."
  [#^NodeMetadata node]
  (state-predicate node RUNNING))

(defn terminated?
  "Predicate for the node being halted."
  [#^NodeMetadata node]
  (state-predicate node TERMINATED))

(defn suspended?
  "Predicate for the node being suspended."
  [#^NodeMetadata node]
  (state-predicate node SUSPENDED))

(defn error-state?
  "Predicate for the node being in an error state."
  [#^NodeMetadata node]
  (state-predicate node ERROR))

(defn unknown-state?
  "Predicate for the node being in an unknown state."
  [#^NodeMetadata node]
  (state-predicate node UNKNOWN))

(defn public-ips
  "Returns the node's public ips"
  [#^NodeMetadata node]
  (map #(.getHostAddress %) (.getPublicAddresses node)))

(defn private-ips
  "Returns the node's private ips"
  [#^NodeMetadata node]
  (map #(.getHostAddress %) (.getPrivateAddresses node)))

(defn tag
  "Returns a the node's tag"
  [#^NodeMetadata node]
  (.getTag node))

(defn hostname
  "Returns the compute node's name"
  [#^ComputeMetadata node]
  (.getName node))

(define-accessors Template image size location options)
(define-accessors Image version os-family os-description architecture)
(define-accessors Size cores ram disk)
(define-accessors NodeMetadata "node" credentials extra state tag)

(defn builder-options [builder]
  (or (wall-hack-field org.jclouds.compute.internal.TemplateBuilderImpl :options builder)
      (TemplateOptions.)))

(defmacro option-option-fn-0arg [key]
  `(fn [builder#]
     (let [options# (builder-options builder#)]
       (~(symbol (str "." (camelize-mixed (name key)))) options#)
       (.options builder# options#))))

(defn- seq-to-array [args]
  (if (or (seq? args) (vector? args))
    (int-array args)
    args))

(defmacro option-option-fn-1arg [key]
  `(fn [builder# value#]
     (let [options# (builder-options builder#)]
       (~(symbol (str "." (camelize-mixed (name key)))) options# (seq-to-array value#))
       (.options builder# options#))))

(def option-1arg-map
     (apply array-map
            (concat
             (make-option-map option-fn-1arg
                              [:os-family :location-id :architecture :image-id :size-id
                               :os-description-matches :image-version-matches
                               :image-description-matches :min-cores :min-ram])
             (make-option-map option-option-fn-1arg
                              [:run-script :install-private-key :authorize-public-key :inbound-ports]))))
(def option-0arg-map
     (apply hash-map
            (concat
             (make-option-map option-fn-0arg
                              [:smallest :fastest :biggest])
             (make-option-map option-option-fn-0arg
                              [:destroy-on-error]))))

(defn os-families []
  (. OsFamily values))
(defn architectures []
  (. Architecture values))

(def enum-map {:os-family (os-families)
               :architecture (architectures)})

(defn add-option-with-value-if [builder kword]
  (loop [enums (sequence enum-map)]
    (if (not (empty? enums))
      (let [enum (first enums)
            value (filter #(= (name kword) (str %)) (second enum))]
        (if (not (empty? value))
          (((first enum) option-1arg-map) builder (first value))
          (recur (rest enums)))))))

(defn add-option-if [builder kword]
  (let [f (option-0arg-map kword)]
    (if f (f builder))))

(defn add-keyword-option [builder option]
  (if (not (or (add-option-with-value-if builder option)
               (add-option-if builder option)))
    (println "Unknown option " option)))

(defn add-value-option [builder option value]
  (let [f (option-1arg-map option)]
    (if f
      (f builder value)
      (println "Unknown option" option))))

(defn build-template [#^ComputeService compute option & options]
  (let [builder (.. compute (templateBuilder))]
    (loop [option option
           remaining options]
      (if (empty? remaining)
        (add-keyword-option builder option)
        (let [next-is-keyword (keyword? (first remaining))
              arg (if (not next-is-keyword)
                    (first remaining))
              next (if next-is-keyword
                     (first remaining)
                     (fnext remaining))
              remaining (if (keyword? (first remaining))
                          (rest remaining)
                          (drop 2 remaining))]
          (if arg
            (add-value-option builder option arg)
            (add-keyword-option builder option))
          (if next
            (recur next remaining)))))
    (.build builder)))

