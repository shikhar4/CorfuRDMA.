ARG BASE_IMAGE=openjdk:8-jdk-bullseye
FROM $BASE_IMAGE

ARG CORFU_JAR
ARG CMDLETS_JAR
ARG CORFU_TOOLS_JAR

WORKDIR /app

RUN apt update && apt -y install iptables bash jq python3 sudo iproute2 python3-pip curl
RUN python3 -m pip install pyyaml==5.4.1
RUN python3 -m pip install netifaces==0.11.0

COPY target/${CORFU_JAR} /usr/share/corfu/lib/${CORFU_JAR}
COPY target/${CMDLETS_JAR} /usr/share/corfu/lib/${CMDLETS_JAR}
COPY target/${CORFU_TOOLS_JAR} /usr/share/corfu/lib/${CORFU_TOOLS_JAR}

COPY target/bin /usr/share/corfu/bin
COPY target/corfu_scripts /usr/share/corfu/corfu_scripts
COPY target/scripts /usr/share/corfu/scripts
COPY target/logback.prod.xml /usr/share/corfu/conf/logback.prod.xml
COPY target/corfu-compactor-config.yml /usr/share/corfu/conf/corfu-compactor-config.yml
COPY target/compactor-logback.prod.xml /usr/share/corfu/conf/compactor-logback.prod.xml

# For integration testing purposes
COPY target/${CORFU_JAR} /app/corfu.jar

ENV JAVA_OPTS -verbose:gc \
    -XX:+PrintGCDetails \
    -XX:+PrintGCTimeStamps \
    -XX:+PrintGCDateStamps \
    -Xloggc:/var/log/corfu/corfu.jvm.gc.9000.log \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+UseGCLogFileRotation \
    -XX:NumberOfGCLogFiles=10 \
    -XX:GCLogFileSize=5M \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/var/log/corfu/corfu_oom.hprof \
    -XX:+HeapDumpOnOutOfMemoryError \
    -Djdk.nio.maxCachedBufferSize=1048576 \
    -Dio.netty.recycler.maxCapacityPerThread=0 \
    -XX:+PrintGCApplicationStoppedTime \
    -XX:+PrintGCApplicationConcurrentTime \
    -Djava.io.tmpdir=/image/corfu-server/temp

CMD java -cp *.jar \
    ${JAVA_OPTS} \
    -Dlogback.configurationFile=/usr/share/corfu/conf/logback.prod.xml \
    org.corfudb.infrastructure.CorfuServer \
    --compactor-script /usr/share/corfu/scripts/compactor_runner.py \
    --compactor-config=/usr/share/corfu/conf/corfu-compactor-config.yml \
    --compaction-trigger-freq-ms=900000
