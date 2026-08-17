// A pure Kotlin/JVM engine module. MASTER_PLAN §4.1's audio-core and
// visual-core are this: no Android, no Media3, no GL.
//
// The plugin choice IS the boundary. A `java-library` module cannot resolve
// android.* at all, so the rule "audio-core must not import Android" stops
// being a convention somebody has to remember and becomes something that does
// not compile - which is the argument ENGINE_V2_PLAN §1 makes from the two
// files in analysis/ that drifted into importing android.* under a package
// convention that could not stop them.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("geode.kotlin-common")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
}
