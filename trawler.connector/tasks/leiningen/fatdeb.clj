(ns leiningen.fatdeb
  (:refer-clojure :exclude [replace])
  (:use [clojure.java.shell :only [sh]]
        [clojure.java.io :only [file delete-file writer copy]]
        [clojure.string :only [join capitalize trim-newline replace]]
        [leiningen.uberjar :only [uberjar]]
        [leiningen.tar :only [md5]])
  (:import java.text.SimpleDateFormat
           java.util.Date))

(defn delete-file-recursively
    "Delete file f. If it's a directory, recursively delete all its contents.
    Raise an exception if any deletion fails unless silently is true."
    [f & [silently]]
    (System/gc) ; This sometimes helps release files for deletion on windows.
    (let [f (file f)]
          (if (.isDirectory f)
                  (doseq [child (.listFiles f)]
                            (delete-file-recursively child silently)))
          (delete-file f silently)))

(defn deb-dir
  "Debian package working directory."
  [project]
  (file (:root project) (str "target/deb/" (:slug project))))

(defn cleanup
  [project]
  ; Delete working dir.
  (when (.exists (deb-dir project))
    (delete-file-recursively (deb-dir project))))

(defn reset
  [project]
  (cleanup project)
  (sh "rm" (str (:root project) "/target/*.deb")))

(def build-date (Date.))
(defn get-version
  [project]
  (let [df   (SimpleDateFormat. "yyyyMMdd-HHmmss")]
    (replace (:version project) #"SNAPSHOT" (.format df build-date))))

(defn control
  "Control file"
  [project]
  (join "\n"
        (map (fn [[k v]] (str (capitalize (name k)) ": " v))
             {:package (:slug project)
              :version (get-version project)
              :section "java"
              :priority "optional"
              :architecture "all"
              :recommends (join ", " ["default-jre-headless (<= 1.7) | java7-runtime"])
              :maintainer (:email (:maintainer project))
              :description (:description project)})))

(defn write
  "Write string to file, plus newline"
  [file string]
  (with-open [w (writer file)]
    (.write w (str (trim-newline string) "\n"))))

(defn make-deb-dir
  "Creates the debian package structure in a new directory."
  [project]
  (let [dir (deb-dir project)]
    (.mkdirs dir)

    ; Meta
    (.mkdirs (file dir "DEBIAN"))
    (write (file dir "DEBIAN" "control") (control project))
    (write (file dir "DEBIAN" "conffiles")
           (join "\n" [(str "/etc/" (:slug project) "/" (:slug project) ".yml" )
                       (str "/etc/default/" (:slug project))
                       (str "/etc/init.d/" (:slug project))]))

    ; Preinst
    (copy (file (:root project) "pkg" "deb" "preinst.sh")
          (file dir "DEBIAN" "preinst"))
    (.setExecutable (file dir "DEBIAN" "preinst") true false)

    ; Postinst
    (copy (file (:root project) "pkg" "deb" "postinst.sh")
          (file dir "DEBIAN" "postinst"))
    (.setExecutable (file dir "DEBIAN" "postinst") true false)

    ; Prerm
    (copy (file (:root project) "pkg" "deb" "prerm.sh")
          (file dir "DEBIAN" "prerm"))
    (.setExecutable (file dir "DEBIAN" "prerm") true false)

    ; Postrm
    (copy (file (:root project) "pkg" "deb" "postrm.sh")
          (file dir "DEBIAN" "postrm"))
    (.setExecutable (file dir "DEBIAN" "postrm") true false)

    ; Jar
    (.mkdirs (file dir "usr" "share" (:slug project)))
    (copy (file (:root project) "target" "uberjar"
                (str (:slug project) ".jar"))
          (file dir "usr" "share" (:slug project) (str (:slug project) ".jar" )))

    ; Binary
    (.mkdirs (file dir "usr" "bin"))
    (copy (file (:root project) "pkg" "deb" (:slug project))
          (file dir "usr" "bin" (:slug project)))
    (.setExecutable (file dir "usr" "bin" (:slug project)) true false)

    ; Log dir
    (.mkdirs (file dir "var" "log" (:slug project)))

    ; Config
    (.mkdirs (file dir "etc" (:slug project)))
    (copy (file (:root project) "pkg" "deb" "config.yml")
          (file dir "etc" (:slug project) (str (:slug project) ".yml" )))

    ; defaults file
    (.mkdirs (file dir "etc" "default"))
    (copy (file (:root project) "pkg" (str (:slug project) "-default" ))
          (file dir "etc" "default" (:slug project)))

    ; Init script
    (.mkdirs (file dir "etc" "init.d"))
    (copy (file (:root project) "pkg" "deb" "init.sh")
          (file dir "etc" "init.d" (:slug project)))
    (.setExecutable (file dir "etc" "init.d" (:slug project)) true false)

    dir))

(defn dpkg
  "Convert given package directory to a .deb."
  [project deb-dir]
  (print (:err (sh "fakeroot" "dpkg" "--build"
                   (str deb-dir)
                   (str (file (:root project) "target")))))
  (let [deb-file-name (str (:slug project) "_"
                           (get-version project) "_" "all"
                           ".deb")
        deb-file (file (:root project) "target" deb-file-name)]
    (write (str deb-file ".md5")
           (str (md5 deb-file) "  " deb-file-name))))

(defn fatdeb
  ([project] (fatdeb project true))
  ([project uberjar?]
   (reset project)
   (when uberjar? (uberjar project))
   (dpkg project (make-deb-dir project))
   (cleanup project)
   (flush)))
