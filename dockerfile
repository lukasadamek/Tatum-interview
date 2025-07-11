# Use official Eclipse Temurin JDK ase base image
FROM eclipse-temurin:17-jdk

# set workdir in container
WORKDIR /app

# Copy JAR file into container
COPY target/EthereumProxyServer-1.0.0.jar ./app.jar

# Optional: add keystore file for TLS (must be in host system)
# COPY keystore.jks ./keystore.jks

# port of TLS server
EXPOSE 8443

# run app
ENTRYPOINT ["java", "-jar", "app.jar"]
