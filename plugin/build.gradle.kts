plugins {
	id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

base {
	archivesName = "pulselink-plugin"
}

lavalinkPlugin {
	name = "pulselink-plugin"
	apiVersion = "4.0.0"
	serverVersion = "4.0.5"
	configurePublishing = false
}

dependencies {
	implementation(project(":main"))
	implementation(project(":protocol"))
	compileOnly("com.github.topi314.lavasearch:lavasearch:1.0.0")
	implementation("com.github.topi314.lavasearch:lavasearch-plugin-api:1.0.0")
	implementation("com.github.topi314.lavalyrics:lavalyrics-plugin-api:1.0.0")

	testImplementation(platform("org.junit:junit-bom:5.10.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.mockito:mockito-core:5.12.0")
	testImplementation("org.springframework:spring-test:6.1.8")
	// Lavalink supplies these APIs at runtime; unit tests need them as well.
	testImplementation("dev.arbjerg.lavalink:plugin-api:4.0.0")
}

tasks {
	jar {
		dependsOn(":main:jar", ":protocol:jar", ":protocol:jvmJar")
		from(zipTree(project(":protocol").layout.buildDirectory.file("libs/pulselink-protocol-jvm-${project.version}.jar")))
	}
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			from(components["java"])
			artifactId = base.archivesName.get()
		}
	}
}
