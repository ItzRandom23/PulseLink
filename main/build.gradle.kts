plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

base {
    archivesName = "pulselink"
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.github.topi314.lavasearch:lavasearch:1.0.0")
    api("com.github.topi314.lavalyrics:lavalyrics:1.0.0")
    compileOnly("dev.arbjerg:lavaplayer:2.0.4")
    compileOnly("com.github.lavalink-devs.youtube-source:common:1.0.5")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("commons-io:commons-io:2.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
    implementation("com.auth0:java-jwt:4.4.0")
    compileOnly("org.slf4j:slf4j-api:2.0.7")

    lyricsDependency("protocol")
    lyricsDependency("client")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.12.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            pom {
                artifactId = base.archivesName.get()
                from(components["java"])
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Javadoc>().configureEach {
    enabled = false
}

tasks.matching { it.name == "javadocJar" }.configureEach {
    enabled = false
}

fun DependencyHandlerScope.lyricsDependency(module: String) {
    implementation("dev.schlaubi.lyrics", "$module-jvm", "2.5.0") {
        isTransitive = false
    }
}
