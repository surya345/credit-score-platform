FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp package -DskipTests
ARG SERVICE
RUN cp ${SERVICE}/target/${SERVICE}-1.0.0.jar /application.jar
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /application.jar /app/application.jar
USER app
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/application.jar"]
