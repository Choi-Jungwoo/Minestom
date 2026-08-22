plugins {
    id("minestom.java-library")
}

// Cloudburst's split fastutil artifacts are not JPMS-safe, so this stays an automatic module.
repositories {
    maven("https://repo.opencollab.dev/main")
}

dependencies {
    api(project(":"))

    // Bedrock 1.26 is only available in timestamped Cloudburst 3.0 builds; these mirror Geyser's pins.
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport)
    implementation(libs.netty.transport.classes.epoll)
    implementation(libs.netty.transport.classes.kqueue)
    implementation(libs.gson)

    implementation(libs.cloudburst.common) {
        exclude(group = "io.netty")
    }
    implementation(libs.cloudburst.codec) {
        exclude(group = "io.netty")
    }
    implementation(libs.cloudburst.connection) {
        exclude(group = "io.netty")
        exclude(group = "org.cloudburstmc.netty", module = "netty-transport-raknet")
    }
    implementation(libs.cloudburst.raknet) {
        exclude(group = "io.netty")
    }

    testImplementation(project(":testing"))
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
}

val bedrockMappingsDirectory = providers.gradleProperty("bedrockMappingsDirectory")
val bedrockMappingsSource = providers.gradleProperty("bedrockMappingsSource")

tasks.register<JavaExec>("verifyMappings") {
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
