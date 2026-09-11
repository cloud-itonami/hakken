FROM clojure:temurin-21-tools-deps

WORKDIR /app
COPY deps.edn ./
RUN clojure -P
COPY src ./src
COPY langgraph.edn ./

ENV LANGSERVER_PORT=8080
EXPOSE 8080

CMD ["kbb", "-M", "-m", "hakken.server"]
