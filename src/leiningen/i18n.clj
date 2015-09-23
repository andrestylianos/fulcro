(ns leiningen.i18n
  (:require [clojure.java.shell :refer [sh]]
            [leiningen.core.main :as lmain]
            [leiningen.cljsbuild :refer [cljsbuild]]
            [clojure.string :as str]
            [untangled.i18n.util :as u]))

(def compiled-js-path "i18n/out/compiled.js")
(def msgs-dir-path "i18n/msgs")
(def cljs-output-dir "src/untangled/translations")
(def messages-pot-path (str msgs-dir-path "/messages.pot"))

(defn find-po-files [msgs-dir-path]
  (filter #(.endsWith % ".po")
          (clojure.string/split-lines
            (:out (sh "ls" msgs-dir-path)))))

(defn gettext-missing? []
  (let [xgettext (:exit (sh "which" "xgettext"))
        msgcat (:exit (sh "which" "msgcat"))]
    (or (> xgettext 0) (> msgcat 0))))

(defn dir-missing? [dir]
  (-> (sh "ls" "-d" dir)
      (get :exit)
      (> 0)))

(defn cljs-prod-build?
  [build]
  (if (= (:id build) "production") build false))

(defn get-cljsbuild [builds]
  (some #(cljs-prod-build? %)
        builds))

(defn configure-i18n-build [build]
  (let [compiler-config (assoc (:compiler build) :output-dir "i18n/out"
                                                 :optimizations :whitespace
                                                 :output-to compiled-js-path)]
    (assoc build :id "i18n" :compiler compiler-config)))

(defn- po-path [po-file] (str msgs-dir-path "/" po-file))

(defn clojure-ize-locale [po-filename]
  (-> po-filename
      (str/replace #"^([a-z]+_*[A-Z]*).po$" "$1")
      (str/replace #"_" "-")))

(defn- puke [msg]
  (lmain/warn msg)
  (lmain/abort))

(defn deploy-translations
  "This subtask converts translated .po files into locale-specific .cljs files for runtime string translation."
  [project]
  (sh "mkdir" "-p" cljs-output-dir)
  (doseq [po (find-po-files msgs-dir-path)]
    (let [locale (clojure-ize-locale po)
          translation-map (u/map-po-to-translations (po-path po))
          cljs-translations (u/wrap-with-swap :locale locale :translation translation-map)
          cljs-trans-path (str cljs-output-dir "/" locale ".cljs")]
      (u/write-cljs-translation-file cljs-trans-path cljs-translations))))

(defn extract-i18n-strings
  "This subtask extracts strings from your cljs files that should be translated."
  [project]
  (if (gettext-missing?)
    (puke "The xgettext and msgcat commands are not installed, or not on your $PATH.")
    (if (dir-missing? msgs-dir-path)
      (puke "The i18n/msgs directory is missing in your project! Please create it.")
      (let [cljsbuilds-path [:cljsbuild :builds]
            builds (get-in project cljsbuilds-path)
            cljs-prod-build (get-cljsbuild builds)
            i18n-build (configure-i18n-build cljs-prod-build)
            i18n-project (assoc-in project cljsbuilds-path [i18n-build])
            po-files-to-merge (find-po-files msgs-dir-path)]

        (cljsbuild i18n-project "once" "i18n")
        (sh "xgettext" "--from-code=UTF-8" "--debug" "-k" "-ktr:1" "-ktrc:1c,2" "-ktrf:1" "-o" messages-pot-path
            compiled-js-path)
        (doseq [po po-files-to-merge]
          (sh "msgcat" "--no-wrap" messages-pot-path (po-path po) "-o" (po-path po)))))))

(defn i18n
  "A plugin which automates your i18n string translation workflow"
  {:subtasks [#'extract-i18n-strings #'deploy-translations]}
  ([project]
   (puke "Bad you!"))
  ([project subtask]
   (case subtask
     "extract" (extract-i18n-strings project)
     "deploy" (deploy-translations project)
     (puke (str "Unrecognized subtask: " subtask)))))


