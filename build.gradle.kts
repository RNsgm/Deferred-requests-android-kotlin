plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("kapt") version "2.2.20" apply false
    id("com.android.library") version "8.11.1" apply false
}

// На JitPack координаты задаёт он сам: com.github.<user>, версия — тег.
val onJitPack = System.getenv("JITPACK") == "true"

allprojects {
    group = if (onJitPack) System.getenv("GROUP") else "io.deferredrequests"
    version = if (onJitPack) System.getenv("VERSION") else "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("Persistent, ordered queue of deferred server requests for Kotlin and Android")
                    url.set("https://github.com/RNsgm/Deferred-requests-android-kotlin")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/RNsgm/Deferred-requests-android-kotlin")
                        connection.set("scm:git:https://github.com/RNsgm/Deferred-requests-android-kotlin.git")
                    }
                }
            }
        }
    }
}
