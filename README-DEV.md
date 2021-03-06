# Somewhat outdated development info follows

## Vert.x Simple Gradle Verticle project

This project is very similar to the gradle-simplest project but instead
of embedding Vert.x it shows an example of writing the code as a
verticle.

You can run it directly in your IDE by creating a run configuration that
uses the main class `io.vertx.core.Launcher` and passes in the arguments
`run org.zanata.proxyhook.server.ProxyHookServer`.

The build.gradle uses the Gradle shadowJar plugin to assemble the
application and all its dependencies into a single "fat" jar.

To build the "fat jar"

    ./gradlew shadowJar

To run the fat jar:

    java -jar build/libs/proxyhook-0.1-SNAPSHOT-fat.jar

(You can take that jar and run it anywhere there is a Java 8+ JDK. It
contains all the dependencies it needs so you don’t need to install
Vert.x on the target machine).

## Vert.x 3.2 Gradle redeploy project

This project shows how to use the Vert.x 3.2 redeploy feature. Vert.x
watches for file changes and will then compile these changes. The
verticle will be redeployed automatically. Simply start the application
with:

    ./gradlew run

Now point your browser at <http://localhost:8080>. Then you can make
changes to the verticle and reload the browser.

The whole configuration for this is rather simple:

    mainClassName = 'io.vertx.core.Launcher'
    def mainVerticleName = 'org.zanata.proxyhook.server.ProxyHookServer'

    // Vert.x watches for file changes in all subdirectories
    // of src/ but only for files with .java extension
    def watchForChange = 'src/**/*.java'

    // Vert.x will call this task on changes
    def doOnChange = './gradlew classes'

    run {
        args = ['run', mainVerticleName, "--redeploy=$watchForChange", "--launcher-class=$mainClassName", "--on-redeploy=$doOnChange"]
    }
