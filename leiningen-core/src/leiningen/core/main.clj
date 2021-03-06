(ns leiningen.core.main
  (:require [leiningen.core.user :as user]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [leiningen.core.utils :as utils]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [bultitude.core :as b]))

(def aliases {"-h" "help", "-help" "help", "--help" "help", "-?" "help",
              "-v" "version", "-version" "version", "--version" "version",
              "überjar" "uberjar",
              ;; TODO: these should use add-profile instead
              "-o" ["with-profile" "offline,dev,user,default"]
              "-U" ["with-profile" "update,dev,user,default"]
              "cp" "classpath" "halp" "help"
              "with-profiles" "with-profile"
              "readme" ["help" "readme"]
              "tutorial" ["help" "tutorial"]
              "sample" ["help" "sample"]})

;; without a delay this loads profiles at the top-level which can
;; result in exceptions thrown outside of a nice catching context.
(def ^:private profile-aliases
  "User profile aliases, used only when Lein is not within a project."
  (delay (atom (-> (user/profiles) :user :aliases))))

(defn- get-and-dissoc!
  "Returns a value associated with a key in a hash map contained in an atom,
  removing it if it exists."
  [atom key]
  (when-let [[k v] (find @atom key)]
    (swap! atom dissoc key)
    v))

(defn lookup-alias [task-name project & [not-found]]
  (or (aliases task-name)
      (get (:aliases project) task-name)
      (when-not project
        (get-and-dissoc! @profile-aliases task-name))
      task-name
      (or not-found "help")))

(defn task-args [args project]
  (if (= "help" (aliases (second args)))
    ["help" [(first args)]]
    [(lookup-alias (first args) project) (rest args)]))

(def ^:dynamic *debug* (System/getenv "DEBUG"))

(defn debug
  "Print if *debug* (from DEBUG environment variable) is truthy."
  [& args]
  (when *debug* (apply println args)))

(def ^:dynamic *info* true)

(defn info
  "Print unless *info* has been rebound to false."
  [& args]
  (when *info* (apply println args)))

(def ^:dynamic *exit-process?*
  "Bind to false to suppress process termination." true)

(defn exit
  "Exit the process. Rebind *exit-process?* in order to suppress actual process
  exits for tools which may want to continue operating. Never call
  System/exit directly in Leiningen's own process."
  ([exit-code]
     (if *exit-process?*
       (do (shutdown-agents)
           (System/exit exit-code))
       (throw (ex-info "Suppressed exit" {:exit-code exit-code}))))
  ([] (exit 0)))

(defn abort
  "Print msg to standard err and exit with a value of 1.
  Will not directly exit under some circumstances; see *exit-process?*."
  [& msg]
  (binding [*out* *err*]
    (when (seq msg)
      (apply println msg))
    (exit 1)))

(defn- distance [s t]
  (letfn [(iters [n f start]
            (take n (map second
                         (iterate f start))))]
    (let [m (inc (count s)), n (inc (count t))
          first-row (vec (range m))
          matrix (iters n (fn [[j row]]
                            [(inc j)
                             (vec (iters m (fn [[i col]]
                                             [(inc i)
                                              (if (= (nth s i)
                                                     (nth t j))
                                                (get row i)
                                                (inc (min (get row i)
                                                          (get row (inc i))
                                                          col)))])
                                         [0 (inc j)]))])
                        [0 first-row])]
      (last (last matrix)))))

(defn tasks
  "Return a list of symbols naming all visible tasks."
  []
  (->> (b/namespaces-on-classpath :prefix "leiningen")
       (filter #(re-find #"^leiningen\.(?!core|main|util)[^\.]+$" (name %)))
       (distinct)
       (sort)))

(defn suggestions
  "Suggest possible misspellings for task from list of tasks."
  [task tasks]
  (let [suggestions (into {} (for [t tasks
                                   :let [n (.replaceAll (name t)
                                                        "leiningen." "")]]
                               [n (distance n task)]))
        min (apply min (vals suggestions))]
    (if (<= min 4)
      (map first (filter #(= min (second %)) suggestions)))))

(defn ^:no-project-needed task-not-found [task & _]
  (binding [*out* *err*]
    (println (str "'" task "' is not a task. See 'lein help'."))
    (when-let [suggestions (suggestions task (tasks))]
      (println)
      (println "Did you mean this?")
      (doseq [suggestion suggestions]
        (println "        " suggestion))))
  (throw (ex-info "Task not found" {:exit-code 1 :suppress-msg true})))

;; TODO: got to be a cleaner way to do this, right?
(defn- drop-partial-args [pargs]
  #(for [[f & r] %
         :let [non-varargs (if (pos? (inc (.indexOf (or r []) '&)))
                             (min (count pargs) (.indexOf r '&))
                             (count pargs))]]
     (cons f (drop non-varargs r))))

(defn resolve-task
  "Look up task function and perform partial application if applicable."
  ([task not-found]
     (let [[task & pargs] (if (coll? task) task [task])]
       (if-let [task-var (utils/require-resolve (str "leiningen." task) task)]
         (with-meta
           (fn [project & args] (apply task-var project (concat pargs args)))
           (update-in (meta task-var) [:arglists] (drop-partial-args pargs)))
         (not-found task))))
  ([task] (resolve-task task #'task-not-found)))

(defn ^:internal matching-arity? [task args]
  (some (fn [parameters]
          (and (if (= '& (last (butlast parameters)))
                 (>= (count args) (- (count parameters) 3))
                 (= (count parameters) (inc (count args))))
               parameters))
        (:arglists (meta task))))

(defn- remove-alias
  "Removes an alias from the specified project and its metadata (which lies
   within :without-profiles) to avoid recursive alias calls."
  [project alias]
  (-> project
      (update-in [:aliases] #(if (map? %) (dissoc % alias) %))
      (vary-meta update-in [:without-profiles :aliases] dissoc alias)
      (vary-meta update-in [:profiles]
                 #(zipmap
                   (keys %)
                   (map (fn [p] (if (map? p) (remove-alias p alias) p))
                        (vals %))))))

(defn apply-task
  "Resolve task-name to a function and apply project and args if arity matches."
  [task-name project args]
  (let [[task-alias] (for [[k v] (:aliases project) :when (= v task-name)] k)
        project (and project (remove-alias project
                                           (or task-alias task-name)))
        task (resolve-task task-name)]
    (when-not (or (:root project) (:no-project-needed (meta task)))
      (abort "Couldn't find project.clj, which is needed for" task-name))
    (when-not (matching-arity? task args)
      (abort "Wrong number of arguments to" task-name "task."
             "\nExpected" (string/join " or " (map next (:arglists
                                                         (meta task))))))
    (debug "Applying task" task-name "to" args)
    (apply task project args)))

(defn resolve-and-apply [project args]
  (let [[task-name args] (task-args args project)]
    (apply-task task-name project args)))

(defn leiningen-version []
  (or (System/getenv "LEIN_VERSION")
      (with-open [reader (-> "META-INF/maven/leiningen/leiningen/pom.properties"
                             io/resource
                             io/reader)]
        (-> (doto (java.util.Properties.)
              (.load reader))
            (.getProperty "version")))))

(defn ^:internal version-satisfies? [v1 v2]
  (let [v1 (map #(Integer. %) (re-seq #"\d+" (first (string/split v1 #"-" 2))))
        v2 (map #(Integer. %) (re-seq #"\d+" (first (string/split v2 #"-" 2))))]
    (loop [versions (map vector v1 v2)
           [seg1 seg2] (first versions)]
      (cond (empty? versions) true
            (= seg1 seg2) (recur (rest versions) (first (rest versions)))
            (> seg1 seg2) true
            (< seg1 seg2) false))))

;; packagers should replace this string!
(def ^:private min-version-warning
  "*** Warning: This project requires Leiningen %s, but you have %s ***

Get the latest version of Leiningen at http://leiningen.org or by executing
\"lein upgrade\".")

(defn- verify-min-version
  [{:keys [min-lein-version]}]
  (when-not (version-satisfies? (leiningen-version) min-lein-version)
    (info (format min-version-warning min-lein-version (leiningen-version)))))

(defn user-agent []
  (format "Leiningen/%s (Java %s; %s %s; %s)"
          (leiningen-version) (System/getProperty "java.vm.name")
          (System/getProperty "os.name") (System/getProperty "os.version")
          (System/getProperty "os.arch")))

(defn- configure-http []
  "Set Java system properties controlling HTTP request behavior."
  (System/setProperty "aether.connector.userAgent" (user-agent))
  (when-let [{:keys [host port non-proxy-hosts]} (classpath/get-proxy-settings)]
    (System/setProperty "http.proxyHost" host)
    (System/setProperty "http.proxyPort" (str port))
    (when non-proxy-hosts
      (System/setProperty "http.nonProxyHosts" non-proxy-hosts)))
  (when-let [{:keys [host port]} (classpath/get-proxy-settings "https_proxy")]
    (System/setProperty "https.proxyHost" host)
    (System/setProperty "https.proxyPort" (str port))))

(defn -main
  "Command-line entry point."
  [& raw-args]
  (try
    (user/init)
    (let [project (project/init-project
                   (if (.exists (io/file "project.clj"))
                     (project/read)
                     (-> (project/make {:eval-in :leiningen :prep-tasks []})
                         project/project-with-profiles
                         (project/init-profiles [:default]))))]
      (when (:min-lein-version project) (verify-min-version project))
      (configure-http)
      (resolve-and-apply project raw-args))
    (catch Exception e
      (if (or *debug* (not (:exit-code (ex-data e))))
        (.printStackTrace e)
        (when-not (:suppress-msg (ex-data e))
          (println (.getMessage e))))
      (exit (:exit-code (ex-data e) 1))))
  (exit 0))
