plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("kapt") version "2.2.20" apply false
    id("com.android.library") version "8.11.1" apply false
}

allprojects {
    group = "io.deferredrequests"
    version = "0.1.0"
}
