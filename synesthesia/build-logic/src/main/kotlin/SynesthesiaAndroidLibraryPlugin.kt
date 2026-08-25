import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

class SynesthesiaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("synesthesia.module.dag")

            extensions.configure(LibraryExtension::class.java) {
                compileSdk = 37
                defaultConfig {
                    minSdk = 29
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_21
                    targetCompatibility = JavaVersion.VERSION_21
                }
            }
        }
        target.dependencies.add("testImplementation", "junit:junit:4.13.2")
        target.dependencies.add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    }
}
