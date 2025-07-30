LABEL maintainer="sflaniga@redhat.com"
# https://access.redhat.com/containers/?tab=overview&platform=docker#/registry.access.redhat.com/redhat-openjdk-18/openjdk18-openshift
FROM registry.access.redhat.com/redhat-openjdk-18/openjdk18-openshift:1.2-6

COPY build/libs/client*-fat.jar /deployments/

# NB run-java.sh will scale heap size to 50% of container memory size

# NB must use the exec form of ENTRYPOINT if you want to add arguments with CMD
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example

# see https://github.com/fabric8io-images/run-java-sh
ENTRYPOINT ["/opt/run-java/run-java.sh"]
