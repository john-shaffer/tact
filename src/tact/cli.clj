(ns tact.cli
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [tact.runner :as runner])
  (:gen-class))

(def ^:private version "0.1.0")

(def ^:private cli-options
  [["-d" "--dir DIR" "Directory to set up and run scenarios in (default: temp dir)"]
   ["-h" "--help" "Show help"]
   ["-o" "--output FILE" "Write JSON summary to FILE"]
   [nil "--version" "Show version"]])

(defn ^:dynamic exit
  ([status] (System/exit status))
  ([status msg]
   (println msg)
   (System/exit status)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (exit 1 (str/join "\n" errors))

      (:help options)
      (exit 0 (str "Usage: tact [options] <scenario.toml>...\n\nOptions:\n" summary))

      (:version options)
      (exit 0 (str "tact " version))

      (empty? arguments)
      (exit 1 (str "Usage: tact [options] <scenario.toml>...\n\nOptions:\n" summary))

      :else
      (runner/run-scenarios! arguments :output (:output options) :dir (:dir options)))))
