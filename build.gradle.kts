plugins {
    java
    `maven-publish`
    signing
}

group = "net.swofty"
version = project.findProperty("version") ?: "0.0.0-SNAPSHOT" // handled by semantic-release

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    compileOnly("org.jetbrains:annotations:26.0.2")

    implementation("redis.clients:jedis:7.5.3")

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
            from(components["java"])
            groupId = "net.swofty"
            artifactId = "AtlasRedisAPI"

            pom {
                name.set("AtlasRedisAPI")
                description.set("Simple but blazingly fast all-purpose Redis API")
                url.set("https://github.com/Swofty-Developments/AtlasRedisAPI")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/Swofty-Developments/AtlasRedisAPI/blob/master/LICENSE.txt")
                    }
                }
                developers {
                    developer {
                        id.set("swofty")
                        name.set("Swofty")
                        url.set("https://github.com/Swofty-Developments")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Swofty-Developments/AtlasRedisAPI.git")
                    developerConnection.set("scm:git:ssh://github.com/Swofty-Developments/AtlasRedisAPI.git")
                    url.set("https://github.com/Swofty-Developments/AtlasRedisAPI")
                }
            }
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
    if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
