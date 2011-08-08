(ns control.core
  (:use [clojure.java.io :only [reader]]
        [clojure.string :only [join blank?]]
        [clojure.walk :only [walk]]
		[clojure.contrib.def :only [defvar- defvar]]))

(def bash-reset "\033[0m")
(def bash-bold "\033[1m")
(def bash-redbold "\033[1;31m")
(def bash-greenbold "\033[1;32m")

(defvar- *runtime* (Runtime/getRuntime))

(defstruct ExecProcess :process :in :err :stdout :stderr)

(defn- spawn
  [cmdarray]
  (let [process (.exec *runtime* cmdarray)
		in (reader (.getInputStream process) :encoding "UTF-8")
		err (reader (.getErrorStream process) :encoding "UTF-8")
		execp (struct ExecProcess process in err)
		pagent (agent execp)]
	(send-off pagent (fn [exec-process] (assoc exec-process :stdout (str (:stdout exec-process) (join "\r\n" (doall (line-seq in)))))))
	(send-off pagent (fn [exec-process] (assoc exec-process :stderr (str (:stderr exec-process) (join "\r\n" (doall (line-seq err)))))))
	pagent))

(defn- await-process
  [pagent]
  (let [execp @pagent
		process (:process execp)
		in (:in execp)
		err (:err execp)]
	(await pagent)
	(.close in)
	(.close err)
	(.waitFor process)))
(defn gen-log
  [host tag content]
  (str bash-redbold host ":" bash-greenbold tag ": " bash-reset (join " " content)))

(defn log-with-tag
  [host tag & content]
  (if (not (blank? (join " " content)))
	(println (gen-log host tag content))))

(defn- not-nil?
  [obj]
  (not (nil? obj)))

(defn exec
  [host user cmdcol]
  (let [pagent (spawn (into-array String (filter not-nil? cmdcol)))
		status (await-process pagent)
		execp @pagent]
	(log-with-tag host "stdout" (:stdout execp))
	(log-with-tag host "stderr" (:stderr execp))
	(log-with-tag host "exit" status)))

(defn ssh-client
  [host user]
  (str user "@" host))

(defn ssh
  [host user cluster cmd]
  (let [ssh-options (:ssh-options cluster)]
	(log-with-tag host "ssh" ssh-options cmd)
	(exec host user ["ssh" ssh-options (ssh-client host user) cmd])))

(defn rsync
  [host user cluster src dst]
  (let [rsync-options (:rsync-options cluster)]
	(log-with-tag host "rsync" rsync-options (str src " ==>" dst))
	(exec host user ["rsync" rsync-options src (str (ssh-client host user) ":" dst)])))

(defn scp
  [host user cluster files remoteDir]
  (let [scp-options (:scp-options cluster)]
	(log-with-tag host "scp" scp-options
	  (join " " (concat files [ " ==> " remoteDir])))
	(exec host user
		  (concat ["scp" scp-options] files [(str (ssh-client host user) ":" remoteDir)]))))



(defvar tasks (atom (hash-map)))
(defvar clusters (atom (hash-map)))

(defmacro deftask
  [name desc arguments & body]
  (let [new-body (map #(concat (list (first %) 'host 'user 'cluster) (rest %)) body)]
	`(swap! tasks assoc ~name ~(list 'fn (vec (concat '[host user cluster] arguments)) (cons 'do new-body)))))

(defn- unquote-cluster [args]
  (walk (fn [item]
		  (cond (and (seq? item) (= `unquote (first item))) (second item)
				(or (seq? item) (symbol? item)) (list 'quote item)
				:else (unquote-cluster item)))
		identity
		args))

(defmacro defcluster
  [name & args]
  `(let [m# (apply hash-map ~(cons 'list (unquote-cluster args)))]
	 (swap! clusters assoc ~name (assoc m# :name ~name))))

(defmacro when-exit
  ([test error] `(when-exit ~test ~error nil))
  ([test error else]
	 `(if ~test
		(do (println ~error) (System/exit 1))
		~else)))

(defn- perform
  [host user cluster task taskName arguments]
  (do
	(println (str bash-bold "Performing " (name taskName) " for " host bash-reset))
	(apply task host user cluster arguments)))

(defn- arg-count [f] (let [m (first (.getDeclaredMethods (class f))) p (.getParameterTypes m)] (alength p)))

(defn do-begin [args]
  (when-exit (< (count args) 2)
			 "Please offer cluster and task name"
			 (let [clusterName (keyword (first args))
				   taskName (keyword (second args))
				   args (next (next args))
				   cluster (clusterName @clusters)
				   user (:user cluster)
				   addresses (:addresses cluster)
				   clients (:clients cluster)
				   task (taskName @tasks)]
			   (when-exit (nil? task) (str "No task named " (name taskName)))
			   (when-exit (and (empty? addresses)  (empty? clients)) (str "Empty clients for cluster " (name clusterName)))
			   (let [task-arg-count (- (arg-count task) 3)]
				 (when-exit (not= task-arg-count (count args)) (str "Task " (name taskName) " just needs " task-arg-count " arguments")))
			   (do
				 (println  (str bash-bold "Performing " (name clusterName) bash-reset))
				 (dorun (map #(perform % user cluster task taskName args) addresses))
				 (dorun (map #(perform (:host %) (:user %) cluster task taskName args) clients))
				 (shutdown-agents)))))


(defn begin
  []
  (do-begin *command-line-args*))
