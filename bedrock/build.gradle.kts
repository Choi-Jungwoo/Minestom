import java.util.Properties

plugins {
    id("minestom.java-library")
}

val example = sourceSets.create("example") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named(example.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}
configurations.named(example.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get())
}

// Cloudburst's split fastutil artifacts are not JPMS-safe, so this stays an automatic module.
repositories {
    maven("https://repo.opencollab.dev/main")
}

val compatibilityManifest =
    layout.projectDirectory.file("../gradle/bedrock-compatibility.properties")
val compatibilityPins =
    Properties().apply {
        compatibilityManifest.asFile.inputStream().use(::load)
    }

fun compatibilityPin(name: String): String =
    requireNotNull(compatibilityPins.getProperty(name)) {
        "Missing Bedrock compatibility pin: $name"
    }

dependencies {
    api(project(":"))

    // Bedrock 1.26 is only available in timestamped Cloudburst 3.0 builds; these mirror Geyser's pins.
    implementation(platform("io.netty:netty-bom:${compatibilityPin("netty")}"))
    implementation("io.netty:netty-codec")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-transport")
    implementation("io.netty:netty-transport-classes-epoll")
    implementation("io.netty:netty-transport-classes-kqueue")
    implementation(libs.gson)

    implementation(
        "org.cloudburstmc.protocol:common:${compatibilityPin("cloudburst.common")}",
    ) {
        exclude(group = "io.netty")
    }
    implementation(
        "org.cloudburstmc.protocol:bedrock-codec:${compatibilityPin("cloudburst.codec")}",
    ) {
        exclude(group = "io.netty")
    }
    implementation(
        "org.cloudburstmc.protocol:bedrock-connection:${compatibilityPin("cloudburst.connection")}",
    ) {
        exclude(group = "io.netty")
        exclude(group = "org.cloudburstmc.netty", module = "netty-transport-raknet")
    }
    implementation(
        "org.cloudburstmc.netty:netty-transport-raknet:${compatibilityPin("cloudburst.raknet")}",
    ) {
        exclude(group = "io.netty")
    }

    testImplementation(project(":testing"))
    testRuntimeOnly(
        "io.netty:netty-transport-native-epoll:${compatibilityPin("netty")}:linux-x86_64",
    )
    testRuntimeOnly(
        "io.netty:netty-transport-native-epoll:${compatibilityPin("netty")}:linux-aarch_64",
    )
    testRuntimeOnly(
        "io.netty:netty-transport-native-kqueue:${compatibilityPin("netty")}:osx-x86_64",
    )
    testRuntimeOnly(
        "io.netty:netty-transport-native-kqueue:${compatibilityPin("netty")}:osx-aarch_64",
    )
}

tasks.processResources {
    from(compatibilityManifest) {
        into("META-INF")
        rename { "minestom-bedrock.properties" }
    }
}

val acceptanceTest = tasks.register<Test>("acceptanceTest") {
    group = "verification"
    description = "Runs the real UDP Bedrock compatibility acceptance suite."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("bedrock-acceptance")
    }
    mustRunAfter(tasks.test)
}

tasks.check {
    dependsOn(acceptanceTest)
    dependsOn(tasks.named(example.classesTaskName))
}

val bedrockMappingsDirectory = providers.gradleProperty("bedrockMappingsDirectory")
val bedrockMappingsSource = providers.gradleProperty("bedrockMappingsSource")
val bedrockCompatibilityFile =
    layout.projectDirectory.dir("..").file(
        providers.gradleProperty("bedrockCompatibilityFile"),
    )

tasks.register<JavaExec>("updateCompatibilityPins") {
    group = "build setup"
    description = "Installs one reviewed immutable Bedrock compatibility manifest."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.minestom.server.bedrock.BedrockMaintenanceTool")
    inputs.file(bedrockCompatibilityFile)
    outputs.file(compatibilityManifest)
    args(
        "update",
        bedrockCompatibilityFile.get().asFile.absolutePath,
        compatibilityManifest.asFile.absolutePath,
    )
}

val verifyMappings = tasks.register<JavaExec>("verifyMappings") {
    group = "verification"
    description = "Verifies an operator-provided Bedrock mapping directory."
    notCompatibleWithConfigurationCache("The mapping path is supplied at execution time.")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.minestom.server.bedrock.BedrockMaintenanceTool")
    inputs.property("bedrockMappingsDirectory", bedrockMappingsDirectory)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("verify", bedrockMappingsDirectory.get())
    })
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Runs the Bedrock mobile-client example on UDP port 19132."
    dependsOn(verifyMappings)
    notCompatibleWithConfigurationCache("The mapping path is supplied at execution time.")
    classpath = example.runtimeClasspath
    mainClass.set("net.minestom.server.bedrock.example.BedrockServerExample")
    jvmArgs("-ea")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(bedrockMappingsDirectory.get())
    })
}

tasks.register<JavaExec>("prepareMappings") {
    group = "build setup"
    description = "Copies and re-verifies an exact Bedrock mapping directory."
    notCompatibleWithConfigurationCache("The mapping paths are supplied at execution time.")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.minestom.server.bedrock.BedrockMaintenanceTool")
    inputs.property("bedrockMappingsSource", bedrockMappingsSource)
    inputs.property("bedrockMappingsDirectory", bedrockMappingsDirectory)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "prepare",
            bedrockMappingsSource.get(),
            bedrockMappingsDirectory.get(),
        )
    })
}

tasks.register<JavaExec>("compatibilityReport") {
    group = "help"
    description = "Reports every immutable Bedrock compatibility pin."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.minestom.server.bedrock.BedrockMaintenanceTool")
    args("report")
}
