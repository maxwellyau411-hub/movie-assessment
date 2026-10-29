import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.ktlint) apply false
}

// Shared Kotlin conventions for both services: JDK 21 toolchain, warnings fail the build, ktlint in `check`.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
                freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            }
        }
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            version.set(
                rootProject.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findVersion("ktlint")
                    .get()
                    .requiredVersion,
            )
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
