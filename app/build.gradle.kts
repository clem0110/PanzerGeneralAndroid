plugins {
    id("com.android.application")
}

android {
    namespace = "com.ssi.panzergeneral"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ssi.panzergeneral"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.2"
    }

    androidResources {
        noCompress += listOf("wav", "mid", "shp", "scn", "set", "stm", "eqp", "bin", "str", "tdb", "brf")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
}
