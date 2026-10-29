extension {
    name = "extensions/jimaku.mpe"
}

android {
    namespace = "app.jimaku.extension"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // The bundle's version, for the User-Agent of gateway reads. Releases set it in gradle.properties.
        buildConfigField("String", "BUNDLE_VERSION", "\"${project.version}\"")
    }

    testOptions {
        // android.util.Log and friends answer defaults in JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
