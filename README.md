## lein-spring

a lein plugin for building an uberjar for spring boot applications, analogous to `spring-boot-maven-plugin`.

## usage

on *project.clj*:

```clojure
(defproject foo "1.0.0"
  :plugins [[lein-spring "1.0.0"]]                         ;; the plugin itself - required
  :main x.y.z.package.class                                ;; qualified name of the main class - required
  :aliases {"build" ["do" ["clean"] ["spring-boot-jar"]]}  ;; alias for leiningen - optional (but a great one to have)
  :aot :all)                                               ;; ahead of time compiling for everything - required
```

## building an uberjar

```sh
$ lein spring-boot-jar
```
