import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

class SynesthesiaAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        pluginManager.apply("synesthesia.module.dag")

        extensions.configure(ApplicationExtension::class.java) {
            compileSdk = 37
            defaultConfig {
                minSdk = 29
                targetSdk = 37
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}
