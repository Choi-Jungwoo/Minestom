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
