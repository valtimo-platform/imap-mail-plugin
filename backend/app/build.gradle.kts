// Imported rather than written as java.time.Duration inline: in the Kotlin DSL `java`
// resolves to the JavaPluginExtension, which shadows the package name.
import java.time.Duration

val kotlinLoggingVersion: String by project
val nettyResolverDnsNativeMacOsVersion: String by project

val valtimoVersion: String by project

dependencies {
    implementation(platform("com.ritense.valtimo:valtimo-dependency-versions:$valtimoVersion"))

    implementation("com.ritense.valtimo:valtimo-dependencies:$valtimoVersion")
    implementation("com.ritense.valtimo:local-mail:$valtimoVersion")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.postgresql:postgresql")
    implementation("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")

    if (System.getProperty("os.arch") == "aarch64") {
        runtimeOnly("io.netty:netty-resolver-dns-native-macos:$nettyResolverDnsNativeMacOsVersion:osx-aarch_64")
    }

    implementation(project(":backend:plugin"))
}

tasks.jar {
    enabled = false
}

apply(from = "../../gradle/environment.gradle.kts")
val configureEnvironment = extra["configureEnvironment"] as (task: ProcessForkOptions) -> Unit

dockerCompose {
    // ./docker-compose.yml in this module is picked up by default. The project name matches
    // the `name:` in that file so that bootRun and ./dev.sh act on the same containers rather
    // than starting a second copy of the stack.
    setProjectName("imap-mail-dev")

    // Left running after bootRun exits: the mailbox is in-memory, and tearing it down would
    // throw away the fixtures and the mail state you were just looking at.
    stopContainers = false
    removeContainers = false
    removeVolumes = false

    // Keycloak sits in a compose profile so that `./dev.sh up --no-keycloak` can reuse one
    // another stack already runs. bootRun has no such option, so it always asks for it.
    // Override with `COMPOSE_PROFILES= ./gradlew :backend:app:bootRun` if 8081 is taken.
    environment.put("COMPOSE_PROFILES", System.getenv("COMPOSE_PROFILES") ?: "keycloak")

    // GreenMail and Keycloak both declare healthchecks; wait for them rather than racing the
    // first mail poll against a mail server that is still binding its ports.
    waitForHealthyStateTimeout = Duration.ofMinutes(3)
}

tasks.bootRun {
    dependsOn("composeUp")
    systemProperty("spring.profiles.include", "dev")
    val t = this
    doFirst {
        configureEnvironment(t)
    }
}
