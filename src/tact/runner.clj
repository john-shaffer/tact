(ns tact.runner
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [toml-clj.core :as toml])
  (:import
   (java.io ByteArrayOutputStream InputStream)
   (java.lang Process ProcessHandle)
   (java.nio.file Path)
   (java.util.concurrent TimeUnit)
   (java.util.stream Stream)))

(def default-timeout
  "Seconds a step may run before it is killed."
  600)

(def ^:private step-keys #{"run" "stdin" "env" "timeout" "expected"})

(def ^:private expected-keys
  #{"exit-code" "files"
    "stdout" "stdout-contains" "stdout-not-contains" "stdout-matches"
    "stderr" "stderr-contains" "stderr-not-contains" "stderr-matches"})

(def ^:private file-spec-keys
  #{"content" "file" "json" "json-open" "exists" "contains" "not-contains" "matches"})

(def ^:private output-tail-lines 40)

(defn- scenario-error [msg]
  (throw (ex-info msg {:type ::scenario-error})))

(defn- check-keys! [what allowed m]
  (when-let [unknown (seq (remove allowed (keys m)))]
    (scenario-error (str "Unknown " what " key(s): " (str/join ", " (sort unknown))))))

(defn- text-values [v]
  (if (sequential? v) v [v]))

(defn- check-text-values! [spec prefix]
  (doseq [kind ["contains" "not-contains" "matches"]
          :let [k (str prefix kind)]
          :when (contains? spec k)
          v (text-values (get spec k))]
    (when-not (string? v)
      (scenario-error (str k " must be a string or an array of strings: " (pr-str v))))
    (when (= "matches" kind)
      (try
        (re-pattern v)
        (catch java.util.regex.PatternSyntaxException e
          (scenario-error (str "Invalid " k " pattern " (pr-str v) ": " (.getDescription e))))))))

(defn- check-env! [env]
  (when (some? env)
    (when-not (map? env)
      (scenario-error "env must be a table"))
    (doseq [[k v] env]
      (when-not (or (string? v) (number? v) (boolean? v))
        (scenario-error (str "env value must be a string, number or boolean: " k))))))

(defn- validate-scenario! [scenario]
  (check-env! (get scenario "env"))
  (doseq [step (get scenario "steps")
          :let [expected (get step "expected")]]
    (check-keys! "step" step-keys step)
    (check-env! (get step "env"))
    (check-keys! "expected" expected-keys expected)
    (check-text-values! expected "stdout-")
    (check-text-values! expected "stderr-")
    (doseq [[path spec] (get expected "files")]
      (check-keys! (str "file assertion (" path ")") file-spec-keys spec)
      (check-text-values! spec "")
      (when-not (boolean? (get spec "exists" true))
        (scenario-error (str "exists must be true or false: " path)))
      (when (and (false? (get spec "exists")) (next spec))
        (scenario-error (str "exists = false cannot be combined with other assertions: " path))))))

(defn- load-scenario [path]
  (-> path slurp toml/read-string))

(defn- work-path
  "Resolves path inside work-dir, refusing paths that would escape it."
  [work-dir path]
  (let [root   (fs/normalize (fs/absolutize work-dir))
        target (fs/normalize (fs/path root path))]
    (if (and (.startsWith ^Path target ^Path root) (not= target root))
      target
      (scenario-error (str "Path must be inside the work dir: " path)))))

(defn- resolve-content [base-dir {:strs [content file]}]
  (if file
    (slurp (str (fs/path base-dir file)))
    content))

(defn- setup-files! [work-dir base-dir files]
  (doseq [[path spec] files]
    (let [target (work-path work-dir path)]
      (fs/create-dirs (fs/parent target))
      (spit (str target) (resolve-content base-dir spec)))))

(defn- expand-env
  "Expands ${NAME} references in env values from base. Unset names expand to
  the empty string, as in the shell."
  [base env]
  (update-vals env
               (fn [v]
                 (str/replace (str v)
                              #"\$\{([A-Za-z_][A-Za-z0-9_]*)\}"
                              #(get base (second %) "")))))

(defn- step-timeout [timeout]
  (if (and (number? timeout) (pos? timeout))
    timeout
    (scenario-error (str "timeout must be a positive number of seconds: " (pr-str timeout)))))

(defn- drain
  "Copies in to a buffer on a background thread, so the pipe can't fill up
  and block the step."
  [in]
  (let [buf (ByteArrayOutputStream.)]
    {:buf  buf
     :done (future (with-open [^InputStream in in] (io/copy in buf)))}))

(defn- drained-str
  "Returns what was drained so far, after giving the copy a moment to finish.
  A background process can keep the pipe open after the step exits."
  [{:keys [^ByteArrayOutputStream buf done]}]
  (try
    (deref done 5000 nil)
    (catch Exception _))
  (.toString buf "UTF-8"))

(defn- kill-tree! [^Process proc]
  (let [descendants (-> (.descendants proc) ^Stream (.iterator) iterator-seq vec)]
    (.destroyForcibly proc)
    (run! #(.destroyForcibly ^ProcessHandle %) descendants)))

(defn- run-step! [work-dir env timeout {:strs [run stdin]}]
  (let [proc   (p/start {:dir (str work-dir) :env env :err :pipe :in :pipe :out :pipe}
                        "bash" "-c" run)
        stdout (drain (p/stdout proc))
        stderr (drain (p/stderr proc))]
    ;; Always close stdin, so commands that read it see EOF instead of waiting forever
    (future
      (with-open [out (io/output-stream (p/stdin proc))]
        (when stdin
          (io/copy (.getBytes ^String stdin "UTF-8") out))))
    (let [finished? (.waitFor proc (long (* 1000 timeout)) TimeUnit/MILLISECONDS)]
      (when-not finished?
        (kill-tree! proc))
      {:stdout    (drained-str stdout)
       :stderr    (drained-str stderr)
       :exit      (when finished? (.exitValue proc))
       :timed-out (not finished?)
       :timeout   timeout})))

(defn- json-open-match? [expected actual]
  (cond
    (map? expected)
    (every? (fn [[k v]] (and (contains? actual k)
                             (json-open-match? v (get actual k))))
            expected)
    (sequential? expected)
    (and (= (count expected) (count actual))
         (every? true? (map json-open-match? expected actual)))
    :else (= expected actual)))

(defn- compare-json [path check actual-str expected-parsed match-fn]
  (let [actual-parsed (json/read-str actual-str)]
    (if (match-fn expected-parsed actual-parsed)
      {:type :pass :path path :check check}
      {:type :fail :path path :check check
       :message  (str "JSON content mismatch: " path)
       :expected (str (json/write-str expected-parsed :indent true) "\n")
       :actual   (str (json/write-str actual-parsed :indent true) "\n")})))

(defn- parse-json-spec [v]
  (if (string? v) (json/read-str v) v))

(def ^:private text-assertions
  [["contains"
    "does not contain expected text"
    str/includes?]
   ["not-contains"
    "contains unexpected text"
    (complement str/includes?)]
   ["matches"
    "does not match pattern"
    #(boolean (re-find (re-pattern (str "(?m)" %2)) %1))]])

(defn- text-checks
  "Checks the contains, not-contains and matches assertions in spec against
  text. prefix selects the keys, e.g. \"stdout-\" for stdout-contains. Each
  value is a string or an array of strings, checked by validate-scenario!."
  [check-base label text spec prefix]
  (for [[kind message pass?] text-assertions
        :let [k (str prefix kind)]
        :when (contains? spec k)
        expected (text-values (get spec k))]
    (if (pass? text expected)
      (assoc check-base :type :pass :check k)
      (assoc check-base :type :fail :check k
             :message (str label " " message)
             :expected expected))))

(defn- check-file [work-dir base-dir path spec]
  (let [full-path (work-path work-dir path)
        exists?   (fs/exists? full-path)]
    (cond
      (false? (get spec "exists"))
      [(if exists?
         {:type :fail :path path :check "exists" :message (str "File exists: " path)}
         {:type :pass :path path :check "exists"})]

      (not exists?)
      [{:type :fail :path path :check "exists" :message (str "File does not exist: " path)}]

      (empty? (dissoc spec "exists"))
      [{:type :pass :path path :check "exists"}]

      :else
      (let [actual-str (slurp (str full-path))]
        (cond-> []
          (some #(contains? spec %) ["content" "file"])
          (conj (let [expected (resolve-content base-dir spec)]
                  (if (= actual-str expected)
                    {:type :pass :path path :check "content"}
                    {:type :fail :path path :check "content"
                     :message  (str "File content mismatch: " path)
                     :expected expected
                     :actual   actual-str})))

          (contains? spec "json")
          (conj (compare-json path "json" actual-str (parse-json-spec (get spec "json")) =))

          (contains? spec "json-open")
          (conj (compare-json path "json-open" actual-str (parse-json-spec (get spec "json-open")) json-open-match?))

          :always
          (into (map #(cond-> % (= :fail (:type %)) (assoc :actual actual-str)))
                (text-checks {:path path} path actual-str spec "")))))))

(defn- check-expected [work-dir base-dir result {:strs [expected]}]
  (if (:timed-out result)
    [{:type :fail :check "timeout"
      :message (str "step timed out after " (:timeout result) "s")}]
    (let [{:strs [files stdout stderr exit-code] :or {exit-code 0}} expected
          file-checks (into [] (mapcat (fn [[path spec]] (check-file work-dir base-dir path spec))) files)
          exit-check  (if (= exit-code (:exit result))
                        {:type :pass :check "exit"}
                        {:type :fail :check "exit" :message "exit code mismatch"
                         :expected exit-code :actual (:exit result)})]
      (cond-> (conj file-checks exit-check)
        (contains? expected "stdout")
        (conj (if (= stdout (:stdout result))
                {:type :pass :check "stdout"}
                {:type :fail :check "stdout" :message "stdout mismatch"
                 :expected stdout :actual (:stdout result)}))

        (contains? expected "stderr")
        (conj (if (= stderr (:stderr result))
                {:type :pass :check "stderr"}
                {:type :fail :check "stderr" :message "stderr mismatch"
                 :expected stderr :actual (:stderr result)}))

        :always
        (-> (into (text-checks {} "stdout" (:stdout result) expected "stdout-"))
            (into (text-checks {} "stderr" (:stderr result) expected "stderr-")))))))

(defn- format-multiline [s indent]
  (->> (str/split-lines s)
       (map #(str indent %))
       (str/join "\n")))

(defn- fail? [check]
  (= :fail (:type check)))

(defn- print-diff [expected actual]
  (if (some #(str/includes? (str %) "\n") [expected actual])
    (do
      (println "    expected:")
      (println (format-multiline (str expected) "      "))
      (when (some? actual)
        (println "    actual:")
        (println (format-multiline (str actual) "      "))))
    (do
      (println (str "    expected: " (pr-str expected)))
      (when (some? actual)
        (println (str "    actual:   " (pr-str actual)))))))

(defn- print-check [check]
  (let [label (if (:path check)
                (str (:path check) " (" (:check check) ")")
                (:check check))]
    (if (fail? check)
      (do
        (println "  ✗" label "-" (:message check))
        (when (contains? check :expected)
          (print-diff (:expected check) (:actual check))))
      (println "  ✓" label))))

(defn- print-output [label s]
  (let [lines (str/split-lines s)
        n     (count lines)]
    (cond
      (str/blank? s)
      (println (str "    " label ": (empty)"))

      (> n output-tail-lines)
      (do
        (println (str "    " label " (last " output-tail-lines " of " n " lines):"))
        (println (format-multiline (str/join "\n" (take-last output-tail-lines lines)) "      ")))

      :else
      (do
        (println (str "    " label ":"))
        (println (format-multiline s "      "))))))

(defn- print-step-failure [{:keys [step run result]}]
  (println (str "  step " step " failed"
                (if (:timed-out result)
                  " (timed out)"
                  (str " (exit " (:exit result) ")"))))
  (println "    command:")
  (println (format-multiline run "      "))
  (print-output "stdout" (:stdout result))
  (print-output "stderr" (:stderr result)))

(defn- run-scenario-in-dir! [path scenario base-dir work-dir default-timeout]
  (when-let [files (get-in scenario ["setup" "files"])]
    (setup-files! work-dir base-dir files))
  (let [work-dir (fs/absolutize work-dir)
        base-env (assoc (into {} (System/getenv)) "TACT_WORK_DIR" (str work-dir))
        env      (expand-env base-env (get scenario "env"))
        timeout  (get scenario "timeout" default-timeout)
        step-results
        (into []
              (map-indexed
                (fn [i step]
                  (let [step-env    (merge env
                                           (expand-env (merge base-env env) (get step "env"))
                                           {"TACT_WORK_DIR" (str work-dir)})
                        result      (run-step! work-dir step-env (step-timeout (get step "timeout" timeout)) step)
                        checks      (mapv #(assoc % :step (inc i))
                                          (check-expected work-dir base-dir result step))
                        step-result {:step (inc i) :run (get step "run") :result result :checks checks}]
                    ;; Print as each step finishes, so long scenarios show progress
                    (run! print-check checks)
                    (when (some fail? checks)
                      (print-step-failure step-result))
                    step-result)))
              (get scenario "steps"))
        failed-steps (filterv #(some fail? (:checks %)) step-results)]
    (println)
    {:name   (get scenario "name")
     :path   (str path)
     :status (if (seq failed-steps) "fail" "pass")
     :checks (->> (mapcat :checks step-results)
                  (filter fail?)
                  (mapv #(-> (dissoc % :type)
                             (assoc :status "fail"))))
     :steps  (mapv (fn [{:keys [step run result]}]
                     (-> (select-keys result [:exit :stdout :stderr :timed-out])
                         (assoc :step step :run run)))
                   failed-steps)}))

(defn run-scenario! [path & {:keys [dir timeout] :or {timeout default-timeout}}]
  (println (str "[" path "]"))
  (try
    (let [scenario (load-scenario path)
          base-dir (fs/parent (fs/canonicalize path))]
      (println "Testing:" (get scenario "name"))
      (validate-scenario! scenario)
      (if dir
        (let [work-dir (fs/path dir)]
          (fs/create-dirs work-dir)
          (run-scenario-in-dir! path scenario base-dir work-dir timeout))
        (fs/with-temp-dir [work-dir {:prefix "tact-"}]
          (run-scenario-in-dir! path scenario base-dir work-dir timeout))))
    (catch Exception e
      (let [message (or (ex-message e) (str e))]
        (println "  ! error -" message)
        (println)
        {:path    (str path)
         :status  "error"
         :message message
         :checks  []}))))

(defn- write-json! [output-file results]
  (let [by-status (frequencies (map :status results))
        data      {:summary  {:pass  (get by-status "pass" 0)
                              :fail  (get by-status "fail" 0)
                              :error (get by-status "error" 0)
                              :skip  (get by-status "skip" 0)}
                   :scenarios results}]
    (fs/create-dirs (fs/parent (fs/absolutize (fs/path output-file))))
    (with-open [w (io/writer output-file)]
      (json/write data w :indent true)
      (.write w "\n"))))

(defn- expand-paths [paths]
  (mapcat (fn [path]
            (if (fs/directory? path)
              (->> (fs/glob path "**.toml") sort (map str))
              [path]))
          paths))

(defn run-scenarios! [paths & {:keys [output dir timeout]}]
  (let [results (mapv #(run-scenario! % :dir dir :timeout timeout) (expand-paths paths))
        passed  (count (filter #(= "pass" (:status %)) results))
        total   (count results)]
    (println (str passed "/" total " scenarios passed"))
    (when output
      (write-json! output results))
    (shutdown-agents)
    (when (< passed total)
      (System/exit 1))))
