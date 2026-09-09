plugins {
    id("com.gradleup.nmcp.settings") version "1.4.4"
}

rootProject.name = "AtlasRedisAPI"

nmcpSettings {
    centralPortal {
        username = System.getenv("OSSRH_USERNAME") ?: ""
        password = System.getenv("OSSRH_PASSWORD") ?: ""
        publishingType = "AUTOMATIC"
    }
}
