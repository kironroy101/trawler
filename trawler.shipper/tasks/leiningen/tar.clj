(ns leiningen.tar
  (:use [clojure.java.shell :only [sh with-sh-dir]]
        [clojure.java.io :only [file delete-file writer copy]]
        [clojure.string :only [join capitalize trim-newline split trim]]
        [leiningen.uberjar :only [uberjar]]))

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

(defn tar-dir
  "Tar package working directory."
  [project]
  (file (:root project) "target" "tar" (str (:slug project) "-" 
                                            (:version project))))

(defn cleanup
  [project]
  ; Delete working dir.
  (when (.exists (file (:root project) "target" "tar"))
    (delete-file-recursively (file (:root project) "target" "tar"))))

(defn reset
  [project]
  (cleanup project)
  (sh "rm" (str (:root project) "/target/*.tar.bz2")))

(defn make-tar-dir
  "Creates the tarball package structure in a new directory."
  [project]
  (let [dir (tar-dir project)]
    (.mkdirs dir)

    ; Jar
    (.mkdirs (file dir "lib"))
    (copy (file (:root project) "target" "uberjar" 
                (str (:slug project) ".jar" ))
          (file dir "lib" (str (:slug project) ".jar" )))

    ; Binary
    (.mkdirs (file dir "bin"))
    (copy (file (:root project) "pkg" "tar" (:slug project))
          (file dir  "bin" (:slug project)))
    (.setExecutable (file dir "bin" (:slug project)) true false)

    ; Config
    (.mkdirs (file dir "etc"))
    (copy (file (:root project) "config.yml")
          (file dir "etc" "config.yml"))
    dir))

(defn write
  "Write string to file, plus newline"
  [file string]
  (with-open [w (writer file)]
    (.write w (str (trim-newline string) "\n"))))

(defn md5
  "Computes the md5 checksum of a file. Returns a hex string."
  [file]
  (-> (->> file
       str
       (sh "md5sum")
       :out)
      (split #" ")
      first
      trim))

(defn compress
  "Convert given package directory to a .tar.gz."
  [project tar-dir]
  (let [filename (str (:slug project)
                      "-"
                      (:version project)
                      ".tar.gz")
        tarball (str (file (:root project)
                       "target"
                       filename))]
    (with-sh-dir (.getParent tar-dir)
                 (print (:err (sh "tar" "czvf" tarball (.getName tar-dir)))))
    (write (str tarball ".md5")
           (str (md5 tarball) "  " filename))))

(defn tar
  ([project] (tar project true))
  ([project uberjar?]
  (reset project)
   (when uberjar? (uberjar project))
   (compress project (make-tar-dir project))
   (cleanup project)))
