plugins {
    id("minestom.java-library")
}

repositories {
    maven("https://repo.opencollab.dev/main")
}

dependencies {
    api(project(":"))

    implementation(platform(libs.netty.bom))
    implementation(libs.netty.codec)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport)

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
