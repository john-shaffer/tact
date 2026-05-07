(ns tact.runner
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [toml-clj.core :as toml]))

(defn- load-scenario [path]
  (-> path slurp toml/read-string))

(defn- resolve-content [base-dir {:strs [content file]}]
  (if file
    (slurp (str (fs/path base-dir file)))
    content))

(defn- setup-files! [work-dir base-dir files]
  (doseq [[path spec] files]
    (let [target (fs/path work-dir path)]
      (fs/create-dirs (fs/parent target))
      (spit (str target) (resolve-content base-dir spec)))))

(defn- run-step! [work-dir {:strs [run stdin]}]
  (let [opts (cond-> {:dir (str work-dir) :err :pipe :out :pipe}
               stdin (assoc :in :pipe))
        proc (p/start opts "bash" "-c" run)]
    (when stdin
      (future
        (with-open [out (io/output-stream (p/stdin proc))]
          (io/copy (.getBytes ^String stdin "UTF-8") out))))
    {:stdout (slurp (p/stdout proc))
     :stderr (slurp (p/stderr proc))
     :exit   @(p/exit-ref proc)}))

(defn- check-file [work-dir base-dir path spec]
  (let [full-path (fs/path work-dir path)]
    (if-not (fs/exists? full-path)
      {:type :fail :path path :message (str "File does not exist: " path)}
      (let [actual   (slurp (str full-path))
            expected (resolve-content base-dir spec)]
        (if (= actual expected)
          {:type :pass :path path}
          {:type :fail :path path
           :message  (str "File content mismatch: " path)
           :expected expected
           :actual   actual})))))

(defn- check-expected [work-dir base-dir result {:strs [expected]}]
  (let [{:strs [files stdout stderr exit-code] :or {exit-code 0}} expected
        file-checks (mapv (fn [[path spec]] (check-file work-dir base-dir path spec)) files)
        exit-check  (if (= exit-code (:exit result))
                      {:type :pass :check "exit"}
                      {:type :fail :check "exit" :message "exit code mismatch"
                       :expected exit-code :actual (:exit result)
                       :stderr (:stderr result)})]
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
               :expected stderr :actual (:stderr result)})))))

(defn- format-multiline [s indent]
  (->> (str/split-lines s)
       (map #(str indent %))
       (str/join "\n")))

(defn- print-diff [expected actual]
  (if (some #(str/includes? (str %) "\n") [expected actual])
    (do
      (println "    expected:")
      (println (format-multiline (str expected) "      "))
      (println "    actual:")
      (println (format-multiline (str actual) "      ")))
    (do
      (println (str "    expected: " (pr-str expected)))
      (println (str "    actual:   " (pr-str actual))))))

(defn- print-check [check]
  (let [label (or (:path check) (:check check) "check")]
    (if (= :pass (:type check))
      (println "  ✓" label)
      (do
        (println "  ✗" label "-" (:message check))
        (when (contains? check :expected)
          (print-diff (:expected check) (:actual check)))
        (when (seq (:stderr check))
          (println "    stderr:")
          (println (format-multiline (:stderr check) "      ")))))))

(defn- run-scenario-in-dir! [path scenario base-dir work-dir]
  (when-let [files (get-in scenario ["setup" "files"])]
    (setup-files! work-dir base-dir files))
  (let [step-results
        (mapv
          (fn [step]
            (let [result (run-step! work-dir step)]
              {:result result
               :checks (check-expected work-dir base-dir result step)}))
          (get scenario "steps"))
        all-checks (mapcat :checks step-results)
        pass?      (every? #(= :pass (:type %)) all-checks)]
    (doseq [check all-checks]
      (print-check check))
    (println)
    {:name   (get scenario "name")
     :path   (str path)
     :status (if pass? "pass" "fail")
     :checks (->> all-checks
                  (remove #(= :pass (:type %)))
                  (mapv #(-> (dissoc % :type :stderr)
                             (assoc :status "fail"))))}))

(defn run-scenario! [path & {:keys [dir]}]
  (let [scenario  (load-scenario path)
        base-dir  (fs/parent (fs/canonicalize path))]
    (println (str "[" path "]"))
    (println "Testing:" (get scenario "name"))
    (if dir
      (let [work-dir (fs/path dir)]
        (fs/create-dirs work-dir)
        (run-scenario-in-dir! path scenario base-dir work-dir))
      (fs/with-temp-dir [work-dir {:prefix "tact-"}]
        (run-scenario-in-dir! path scenario base-dir work-dir)))))

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

(defn run-scenarios! [paths & {:keys [output dir]}]
  (let [results (mapv #(run-scenario! % :dir dir) (expand-paths paths))
        passed  (count (filter #(= "pass" (:status %)) results))
        total   (count results)]
    (println (str passed "/" total " scenarios passed"))
    (when output
      (write-json! output results))
    (shutdown-agents)
    (when (< passed total)
      (System/exit 1))))
