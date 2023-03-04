(ns leiningen.spring-boot-jar
  (:require
   [clojure.java.io :as io]
   [clojure.string :as s]
   [leiningen.jar :as jar]
   [leiningen.compile :as compile]
   [leiningen.core.project :as project]
   [leiningen.core.main :as main]
   [leiningen.core.classpath :as classpath])
  (:import
   (java.io BufferedOutputStream ByteArrayInputStream File FileOutputStream)
   (java.util.jar JarEntry JarFile JarOutputStream Manifest)
   (java.util.zip CRC32 ZipEntry)
   (java.nio.file Files Path Paths)))

(defn- cp [src target]
  (io/copy src target :buffer-size 8192))

(defn- path ^String [& args]
  (apply str (interpose File/separator args)))

(defn- relativize ^String [^File dir ^File absolute-file]
  (str (.relativize (.toPath dir) (.toPath absolute-file))))

(defn- paths-get ^Path [& args]
  (Paths/get (first args) (into-array String (rest args))))

(defn- copy-deps [project ^File target-dir]
  (let [project (project/unmerge-profiles project [:dev :provided])
        deps (->> (classpath/resolve-dependencies :dependencies project)
                  (filter #(.exists %)))]
    (.mkdirs target-dir)
    (doseq [dep deps]
      (let [target-file (File. target-dir (.getName dep))]
        (cp dep target-file)))
    (main/info (count deps) "libraries copied to:" (.getAbsolutePath target-dir))))

(defn- copy-classes [^File classes-dir ^File target-dir]
  (let [classes (filter #(.isFile %) (file-seq classes-dir))]
    (doseq [c classes]
      (let [target-file (.toFile (paths-get (str target-dir) (relativize classes-dir c)))]
        (.mkdirs (.getParentFile target-file))
        (cp c (.toFile (paths-get (str target-dir) (relativize classes-dir c))))))
    (main/info (count classes) "classes copied to:" (.getAbsolutePath target-dir))))

(defn- resolve-boot-loader-jar ^JarFile [project ^File target-dir]
  (let [dep (first (classpath/resolve-dependencies :boot-loader (assoc project :boot-loader [['org.springframework.boot/spring-boot-loader (:spring-boot-loader-version project)]])))
        target-file (io/file target-dir (.getName dep))]
    (.mkdirs target-dir)
    (main/info "Using loader:" (str dep))
    (io/copy dep target-file :buffer-size 8192)
    (JarFile. target-file)))

(defn- extract-boot-loader-jar [^File target-dir ^File jar-file]
  (doseq [j (filter #(and
                      (s/starts-with? (.getName %) "org/")
                      (not (s/ends-with? (.getName %) "/")))
                    (enumeration-seq (.entries jar-file)))]
    (let [target-file (io/file (str target-dir "/" (.getName j)))]
      (.mkdirs (.getParentFile target-file))
      (cp (.getInputStream jar-file j) target-file))))

(defn- jar-file-name [project]
  (-> project
      (jar/get-jar-filename)
      (io/file)
      (.getName)
      (str)))

(defn- build-jar [project ^File launcher-jar-classes ^File boot-inf]
  (let [target-dir (:target-path project)
        jar-name (jar-file-name project)
        target-jar-path (path target-dir jar-name)]
    (main/info "Building:" jar-name)
    (main/debug "output path:" target-jar-path)
    (with-open [jar-os (-> target-jar-path
                           (FileOutputStream.)
                           (BufferedOutputStream.)
                           (JarOutputStream.
                            (->> (merge (:manifest project)
                                        {"Start-Class" (:main project)
                                         "Spring-Boot-Classes" "BOOT-INF/classes/"
                                         "Spring-Boot-Lib" "BOOT-INF/lib/"
                                         "Main-Class" "org.springframework.boot.loader.JarLauncher"})
                                 (map (fn [[k v]] (str (name k) ": " v \newline)))
                                 (cons "Manifest-Version: 1.0\n")
                                 (s/join "")
                                 (.getBytes)
                                 (ByteArrayInputStream.)
                                 (Manifest.))))]
      (.setLevel jar-os (long 0))

      (doseq [org-class (file-seq launcher-jar-classes)]
        (let [p (relativize launcher-jar-classes org-class)]
          (main/debug "packaging:" p)
          (when (not (s/blank? p))
            (if (.isDirectory org-class)
              (.putNextEntry jar-os (doto (JarEntry. (str p "/"))
                                      (.setTime (.lastModified org-class))))
              (let [bytes (Files/readAllBytes (.toPath org-class))]
                (.putNextEntry jar-os (doto (JarEntry. (str p))
                                        (.setTime (.lastModified org-class))
                                        (.setMethod (ZipEntry/STORED))
                                        (.setSize (count bytes))
                                        (.setCrc (.getValue (doto (CRC32.) (.update bytes))))))
                (cp org-class jar-os))))))

      (doseq [boot-file (file-seq boot-inf)]
        (let [p (relativize (.getParentFile boot-inf) boot-file)]
          (main/debug "packaging:" p)
          (when (not (s/blank? p))
            (if (.isDirectory boot-file)
              (.putNextEntry jar-os (doto (JarEntry. (str p "/"))
                                      (.setTime (.lastModified boot-file))))
              (let [bytes (Files/readAllBytes (.toPath boot-file))]
                (.putNextEntry jar-os (doto (JarEntry. (str p))
                                        (.setTime (.lastModified boot-file))
                                        (.setMethod (ZipEntry/STORED))
                                        (.setSize (count bytes))
                                        (.setCrc (.getValue (doto (CRC32.) (.update bytes))))))
                (cp boot-file jar-os)))))))))

(defn- execution-time-message [start]
  (str (- (System/currentTimeMillis) start) "ms"))

(defn spring-boot-jar [project & args]
  (let [start (System/currentTimeMillis)
        _ (compile/compile project)
        spring-boot-loader-version-default "3.0.2"
        target-dir (:target-path project)
        boot-inf (path target-dir "boot-jar" "BOOT-INF")
        lib-path (path boot-inf "lib")
        classes (:compile-path project)
        classes-path (path boot-inf "classes")
        launcher-jar-dir (path target-dir "boot-launcher-jar")
        launcher-jar-classes (path target-dir "boot-launcher-classes")]
    (copy-deps project (io/file lib-path))
    (copy-classes (io/file classes) (io/file classes-path))
    (->> (resolve-boot-loader-jar
          (update project :spring-boot-loader-version (fnil identity spring-boot-loader-version-default))
          (io/file launcher-jar-dir))
         (extract-boot-loader-jar (io/file launcher-jar-classes)))
    (build-jar project (io/file launcher-jar-classes) (io/file boot-inf))
    (main/info "Done!" (execution-time-message start) "elapsed")))
