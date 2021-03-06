# first build stage -- will be discarded
FROM clojure AS builder
WORKDIR /app
COPY ./project.clj ./
COPY . .
RUN lein with-profile release uberjar

# second stage -- executable
FROM openjdk:alpine
WORKDIR /usr/src/app
COPY --from=builder /app/target/kamal.jar ./target/kamal.jar
EXPOSE 3000
CMD java $JAVA_OPTIONS -XX:+PrintFlagsFinal -jar target/kamal.jar
