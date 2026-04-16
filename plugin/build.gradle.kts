plugins {
	id("dev.arbjerg.lavalink.gradle-plugin") version "1.0.15"
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
	compileOnly("com.github.lavalink-devs.youtube-source:common:1.0.5")
	compileOnly("com.github.topi314.lavasearch:lavasearch:1.0.0")
	implementation("com.github.topi314.lavasearch:lavasearch-plugin-api:1.0.0")
	implementation("com.github.topi314.lavalyrics:lavalyrics-plugin-api:1.0.0")

	// Copy lyrics.kt from main
	project.project(":main").configurations["implementation"].dependencies.forEach {
		if (it.group == "dev.schlaubi.lyrics") {
			add("implementation", it)
		}
	}

	testImplementation(platform("org.junit:junit-bom:5.10.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.mockito:mockito-core:5.12.0")
	testImplementation("org.springframework:spring-test:6.1.8")
}

tasks {
	jar {
		exclude("dev/schlaubi/lyrics/LyricsClient*")
		exclude("dev/schlaubi/lyrics/Lyrics_jvmKt.class")
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
