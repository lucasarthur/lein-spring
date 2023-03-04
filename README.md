## lein-spring

a lein plugin for building an uberjar for spring boot applications, analogous to `spring-boot-maven-plugin`.

## usage

on *project.clj*:

```clojure
(defproject foo "1.0.0"
  :plugins [[lein-spring "1.0.0"]])
```

# building an uberjar

```sh
lein spring-boot-jar
```
