ARG TEMURIN_IMAGE=eclipse-temurin:25.0.3_9-jre-noble
FROM ${TEMURIN_IMAGE}

RUN groupadd --gid 10001 stratus \
    && useradd --uid 10001 --gid stratus --no-create-home --shell /usr/sbin/nologin stratus \
    && mkdir -p /opt/stratus /evidence \
    && chown -R stratus:stratus /opt/stratus /evidence

COPY --chown=stratus:stratus verification/storage/target/stratus-storage-verifier.jar /opt/stratus/storage-verifier.jar

USER 10001:10001
WORKDIR /opt/stratus

CMD ["java", "-jar", "/opt/stratus/storage-verifier.jar"]
