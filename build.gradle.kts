plugins {
    java
    `maven-publish`
}

group = "net.swofty"
version = project.findProperty("version") ?: "0.0.0-SNAPSHOT" // handled by semantic-release

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    compileOnly("org.jetbrains:annotations:26.0.2")

    implementation("redis.clients:jedis:7.2.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.register("printVersion") {
    doLast {
        println(project.version)
    }
}

tasks.jar {
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "net.swofty"
            artifactId = "AtlasRedisAPI"
            from(components["java"])
            pom {
                name.set("AtlasRedisAPI")
                description.set("Simple but blazingly fast all-purpose Redis API")
                url.set("https://github.com/Swofty-Developments/AtlasRedisAPI")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/Swofty-Developments/AtlasRedisAPI/blob/master/LICENSE.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Swofty-Developments/AtlasRedisAPI")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
