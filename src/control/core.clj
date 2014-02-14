(ns control.core
  (:import [control ExecuteResult ExecuteResult])
  #^{ :doc "Clojure control core"
     :author " Dennis Zhuang <killme2008@gmail.com>"}
  (:use [clojure.java.io :only [reader]]
        [clojure.java.shell :only [sh]]
        [clojure.string :only [join blank? split]]
        [clojure.walk :only [walk postwalk]])

  (:import (com.jcraft.jsch JSch Session)
           (java.util Properties)
           (control SSHUtils ExecuteResult)
           )
  )

(def ^:dynamic *enable-color* true)
;;Error mode,:exit or :exception.
(def ^:dynamic *error-mode* :exit)
(def ^{:dynamic true} *enable-logging* true)
(def ^:dynamic *debug* false)
(def ^:private bash-reset "\033[0m")
(def ^:private bash-bold "\033[1m")
(def ^:private bash-redbold "\033[1;31m")
(def ^:private bash-greenbold "\033[1;32m")
;;Global options for ssh,scp and rsync
(def ^{:dynamic true :private true} *global-options* (atom {}))

(defn error [msg]
  (if (= :exit (or (:error-mode @*global-options*) *error-mode*))
    (do (doto System/err (.println msg)) (System/exit 1))
    (throw (RuntimeException. msg))))

(defn ^:private check-valid-options
  "Throws an exception if the given option map contains keys not listed
  as valid, else returns nil."
  [options & valid-keys]
  (when (seq (apply disj (apply hash-set (keys options)) valid-keys))
    (error (apply str "Only these options are valid: "
             (first valid-keys)
             (map #(str ", " %) (rest valid-keys))))))

(defmacro ^:private cli-bash-bold [& content]
  `(if *enable-color*
     (str bash-bold ~@content bash-reset)
     (str ~@content)))

(defmacro ^:private cli-bash-redbold [& content]
  `(if *enable-color*
     (str bash-redbold ~@content bash-reset)
     (str ~@content)))

(defmacro ^:private cli-bash-greenbold [& content]
  `(if *enable-color*
     (str bash-greenbold ~@content bash-reset)
     (str ~@content)))

(defstruct ^:private ExecProcess  :stdout :stderr :status)

(defn gen-log [host tag content]
  (str (cli-bash-redbold host ":")
       (cli-bash-greenbold tag ": ")
       (join " " content)))

(defn-  log-with-tag [host tag & content]
  (when (and *enable-logging* (not (blank? (join " " content))))
    (println (gen-log host tag content))))

(defn local
  "Execute command on local machine"
  [cmd]
  (when *enable-logging* (println (cli-bash-bold "Performing " cmd " on local")))
  (let [rt (apply sh ["sh" "-c" cmd])
        status (:exit rt)
        stdout (:out rt)
        stderr (:err rt)
        execp (struct-map ExecProcess :stdout stdout :stderr stderr :status status)]
    (log-with-tag "[Local]" "stdout" (:stdout execp))
    (log-with-tag "[Local]" "stderr" (:stderr execp))
    (log-with-tag "[Local]" "exit" status)
    execp))

(defn  ^:dynamic  exec [host user cmdcol]
  (let [rt (apply sh (remove nil? cmdcol))
        status (:exit rt)
        stdout (:out rt)
        stderr (:err rt)
        execp (struct-map ExecProcess :stdout stdout :stderr stderr :status status)]
    (log-with-tag host "stdout" (:stdout execp))
    (log-with-tag host "stderr" (:stderr execp))
    (log-with-tag host "exit" status)
    execp))

(defn ssh-client [host user]
  (if-let [user (or user (:user @*global-options*))]
    (str user "@" host)
    (error "user is nil")))

(defn-  user-at-host? [host user]
  (fn [m]
    (and (= (:user m) user) (= (:host m) host))))

(defn- find-client-options [host user cluster opt]
  (let [opt (keyword opt)
        m (first (filter (user-at-host? host user) (:clients cluster)))]
    (or (opt m) (opt cluster) (opt @*global-options*))))

(defn- make-cmd-array
  [cmd options others]
  (if (vector? options)
    (concat (cons cmd options) others)
    (cons cmd (cons options others))))

(defn set-options!
  "Set global options for ssh,scp and rsync,
   key and value  could be:

      Key                               Value
  :ssh-options        a ssh options string,for example \"-o ConnectTimeout=3000\"
  :scp-options       a scp options string
  :rsync-options    a rsync options string.
  :user                    global user for cluster,if cluster do not have :user ,it will use this by default.
  :parallel               if to execute task on remote machines in parallel,default is false
  :error-mode      mode-keyword,:exit (exit when error happends,the default error mode). or :exception (throw an exception).

  Example:
        (set-options! :ssh-options \"-o ConnectTimeout=3000\")

  "
  [key value & kvs]
  (let [options (apply hash-map key value kvs)]
    (check-valid-options options :user :ssh-options :scp-options :rsync-options :parallel :error-mode)
    (swap! *global-options* merge options)))

(defn clear-options!
  "Clear global options"
  []
  (reset! *global-options* {}))

(defn- do-ssh [host user command]
  (let [^ExecuteResult execute-result (SSHUtils/sshExecute host user command)
        ]
    (log-with-tag host "stdout" (.getStdout execute-result))
    (log-with-tag host "stderr" (.getStderr execute-result))
    (log-with-tag host "exit" (.getStatus execute-result))
    )
  )

(defn ssh
  "Execute commands via ssh:
   (ssh \"date\")
   (ssh \"ps aux|grep java\")
   (ssh \"sudo apt-get update\" :sudo true)

   Valid options:
   :sudo   whether to run commands as root,default is false
   :ssh-options  -- ssh options string
"

  {:arglists '([cmd & opts])}
  [host user cluster cmd & opts]
  (println cluster)
  (let [m (apply hash-map opts)
        sudo (:sudo m)
        cmd (if sudo
              (str "sudo " cmd)
              cmd)
        ssh-options (or (:ssh-options m) (find-client-options host user cluster :ssh-options))]
    (check-valid-options m :sudo :ssh-options :mode :scp-options)
	(log-with-tag host "ssh" ssh-options cmd)
    (do-ssh user host cmd)
    ))



(defn rsync
  "Rsync local files to remote machine's files,for example:
     (deftask :deploy \"scp files to remote machines\" []
    (rsync \"src/\" \":/home/login\"))

    Valid options:
    :rsync-options  -- rsync options string
  "
  {:arglists '([src dst & opts])}
  [host user cluster src dst & opts]
  (let [m (apply hash-map opts)
        rsync-options (or (:rsync-options m) (find-client-options host user cluster :rsync-options))]
    (check-valid-options m :rsync-options)
    (log-with-tag host "rsync" rsync-options (str src " ==>" dst))
    (exec host
          user
          (make-cmd-array "rsync"
                          rsync-options
                          [src (str (ssh-client host user) ":" dst)]))))

(def ^{:dynamic true} *tmp-dir* nil)

(defn scp
  "Copy local files to remote machines:
   (scp \"test.txt\" \"remote.txt\")
   (scp [\"1.txt\" \"2.txt\"] \"/home/deploy/\" :sudo true :mode 755)

  Valid options:
    :sudo  -- whether to copy files to remote machines as root
    :mode -- files permission on remote machines
    :scp-options -- scp options string
"
  {:arglists '([local remote & opts])}
  [host user cluster local remote & opts]
  (let [files (if (coll? local)
                (vec local)
                [local])
        m (apply hash-map opts)
        scp-options (or (:scp-options m) (find-client-options host user cluster :scp-options))
        mode (:mode m)
        sudo (:sudo m)
        use-tmp (or sudo mode)
        tmp (if use-tmp
              (or *tmp-dir* (str "/tmp/control-" (System/currentTimeMillis) "/"))
              remote)]
    (check-valid-options m :scp-options :sudo :mode :ssh-options)
    (log-with-tag host "scp" scp-options
      (join " " (concat files [ " ==> " tmp])))
    (when use-tmp
      (ssh host user cluster (str "mkdir -p " tmp)))
    (let [rt (exec host
                   user
                   (make-cmd-array "scp"
                                   scp-options
                                   (concat files [(str (ssh-client host user) ":" tmp)])))]
      (when mode
        (apply ssh host user cluster (str "chmod " mode  " " tmp "*") opts))
      (if use-tmp
        (apply ssh host user cluster (str "mv "  tmp "* " remote " ; rm -rf " tmp) opts)
        rt))))

;;All tasks defined in control file
(defonce tasks (atom (hash-map)))
;;All clusters defined in control file
(defonce clusters (atom (hash-map)))

(def ^:private system-functions
  #{(symbol "scp") (symbol "ssh") (symbol "rsync") (symbol "call") (symbol "exists?")})

(defmacro
  ^{:doc "Define a task for executing on remote machines:
           (deftask :date \"Get date from remote machines\"
                     (ssh \"date\"))

          Please see https://github.com/killme2008/clojure-control/wiki/Define-tasks
"
    :arglists '([name doc-string? [params*] body])
    :added "0.1"}
  deftask [tname & decl ]
  (let [tname (keyword tname)
        m (if (string? (first decl))
            (next decl)
            decl)
        arguments (first m)
        body (next m)
        new-body (postwalk (fn [item]
                             (if (list? item)
                               (let [cmd (first item)]
                                 (if (and (symbol? cmd) (get system-functions cmd))
                                   (list* cmd  '&host '&user 'cluster (rest item))
                                   item))
                               item))
                           body)]
    (when-not (vector? arguments)
      (error (format "Task %s's arguments must be a vector" (name tname))))
    (when *debug*
      (prn tname "new-body:" new-body))
    `(swap! tasks
            assoc
            ~tname
            ~(list 'fn
                   (vec (concat '[&host &user cluster] arguments))
                   (cons 'do new-body)))))

(defn call
  "Call other tasks in deftask,for example:
     (call :ps \"java\")"
  {:arglists '([task & args])}
  [host user cluster task & args]
  (apply
   (task @tasks)
   host user cluster args))

(defn exists?
  "Check if a file or directory is exists"
  {:arglists '([file])}
  [host user cluster file]
  (zero? (:status (ssh host user cluster (str "test -e " file)))))


(defn- unquote-cluster [args]
  (walk (fn [item]
          (cond (and (seq? item) (= `unquote (first item)))
                (second item)
                (or (seq? item) (symbol? item))
                (list 'quote item)
                :else
                (unquote-cluster item)))
        identity
        args))

(defmacro
  ^{:doc "Define a cluster including some remote machines,for example:
           (defcluster :mycluster
                     :user \"login\"
                     :addresses [\"a.domain.com\" \"b.domain.com\"])

      Please see https://github.com/killme2008/clojure-control/wiki/Define-clusters
     "
    :arglists '([cname & options])
    :added "0.1"}
  defcluster [cname & args]
  (let [cname (keyword cname)]
    `(let [m# (apply hash-map ~(cons 'list (unquote-cluster args)))]
       (swap! clusters assoc ~cname (assoc m# :name ~cname)))))

(defmacro ^:private when-exit
  ([test error]
     `(when-exit ~test ~error nil))
  ([test error else]
     `(if ~test
        (do (error ~error))
        ~else)))

(defn- perform [host user cluster task taskName arguments]
  (do (when *enable-logging* (println (cli-bash-bold "Performing " (name taskName) " for " (ssh-client host user))))
      (apply task host user cluster arguments)))

(defn- arg-count [f]
  (let [m (first (filter #(= (.getName %) "invoke") (.getDeclaredMethods (class f))))
        p (when m (.getParameterTypes m))]
    (if p
      (alength p)
      3)))

(defn- is-parallel? [cluster]
  (or (:parallel cluster) (:parallel @*global-options*)))

(defn- create-clients [arg]
  (when (> (.indexOf ^String arg "@") 0)
    (let [a (split arg #"@")
          user (first a)
          host (second a)]
      (when-exit (nil? user) "user is nil")
      (when-exit (nil? host) "host is nil")
      [{:user user :host host}])))

(defn do-begin [args]
  (when-exit (< (count args) 2)
             "Please offer cluster and task name"
             (let [cluster-name (keyword (first args))
                   task-name (keyword (second args))
                   task-args (next (next args))
                   cluster (cluster-name @clusters)
                   parallel (is-parallel? cluster)
                   user (:user cluster)
                   addresses (:addresses cluster)
                   clients (:clients cluster)
                   task (task-name @tasks)
                   includes (:includes cluster)
                   debug (:debug cluster)
                   log (or (:log cluster) true)
                   clients (if (nil? cluster) (create-clients (first args)) clients)]
               (check-valid-options cluster :user :clients :addresses :parallel :includes :debug :log :ssh-options :scp-options :rsync-options :name :options)
               ;;if task is nil,exit
               (when-exit (nil? task)
                          (str "No task named " (name task-name)))
               (when-exit (and (empty? addresses)
                               (empty? includes)
                               (empty? clients))
                          (str "Empty hosts for cluster "
                               (name cluster-name)))
               ;;check task arguments count
               (let [task-arg-count (- (arg-count task) 3)]
                 (when-exit (> task-arg-count (count task-args))
                            (str "Task "
                                 (name task-name)
                                 " just needs "
                                 task-arg-count
                                 " arguments")))
               (binding [*enable-logging* (and *enable-logging* log)
                         *debug* debug]
                 (when *enable-logging*
                   (println  (str bash-bold
                                  "Performing "
                                  (name cluster-name)
                                  bash-reset
                                  (when parallel
                                    " in parallel"))))
                 (let [map-fn (if parallel pmap map)
                       a (doall (map-fn (fn [addr] [addr (perform addr user cluster task task-name task-args)])
                                        addresses))
                       c (doall (map-fn (fn [cli] [(:host cli) (perform (:host cli) (:user cli) cluster task task-name task-args)])
                                        clients))]
                   (merge (into {} (concat a c))
                          (when includes
                            (if (coll? includes)
                              (mapcat #(do-begin (cons % (next args))) includes)
                              (do-begin (cons (name includes) (next args)))))))))))

(defn begin []
  (do-begin *command-line-args*))
