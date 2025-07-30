LABEL maintainer="sflaniga@redhat.com"
# https://access.redhat.com/containers/?tab=overview&platform=docker#/registry.access.redhat.com/redhat-openjdk-18/openjdk18-openshift
FROM registry.access.redhat.com/redhat-openjdk-18/openjdk18-openshift:1.1-13

COPY build/libs/server*-fat.jar /deployments/
EXPOSE 8080

# http://vertx.io/docs/vertx-infinispan/java/#_configuring_for_kubernetes_or_openshift_3
# NB run-java.sh will scale heap size to 50% of container memory size
ENV JAVA_OPTIONS "-Dhttp.address=0.0.0.0 \
    -Djava.net.preferIPv4Stack=true \
    -Dvertx.jgroups.config=default-configs/default-jgroups-kubernetes.xml"

# You should run this docker container with the env var KUBERNETES_NAMESPACE set to your project name
# eg docker run --memory 128m -e KUBERNETES_NAMESPACE=proxyhook-server --rm -it <image-name>

# NB must use the exec form of ENTRYPOINT if you want to add arguments with CMD
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example

# see https://github.com/fabric8io-images/run-java-sh
ENTRYPOINT ["/opt/run-java/run-java.sh"]
# NB run with arg "-cluster" if you want OpenShift/Kubernetes clustering
