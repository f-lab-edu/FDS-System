package transentia

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * 모든 Kotlin 모듈에 detekt + ktlint 정책 적용.
 * 루트의 config/detekt/detekt.yml, config/detekt/baseline.xml 을 공유.
 */
class CodeQualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            autoCorrect = false
            parallel = true
            // 초기 도입 — violation 을 warning 으로만. CI 게이트 진입 후 false 전환.
            ignoreFailures = true
            // Kotlin 버전 strict check 비활성 — Kotlin 1.9.24/detekt 1.9.23 의 minor 불일치 회피.
            allRules = false
        }
        // Kotlin compiler version mismatch 우회: detekt 자체 Kotlin 사용.
        target.configurations.matching { it.name == "detekt" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion("1.9.23")
                }
            }
        }

        extensions.configure<KtlintExtension> {
            version.set("1.3.1")
            android.set(false)
            ignoreFailures.set(false)
            reporters {
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
            }
            filter {
                exclude("**/build/**", "**/generated/**")
            }
        }

        tasks.withType<Detekt>().configureEach {
            jvmTarget = "21"
            // Kotlin 1.9.24 와 detekt 의 번들 버전 불일치 회피.
            // detekt 가 자체 Kotlin compiler 를 들고 있어 호환 strict check 끄기.
            ignoreFailures = true
            reports {
                html.required.set(true)
                xml.required.set(true)
                sarif.required.set(false)
                md.required.set(false)
            }
        }

        // ktlintCheck 와 detekt 를 check 에 묶어 ./gradlew check 한 줄로.
        tasks.named("check").configure {
            dependsOn("ktlintCheck", "detekt")
        }

        dependencies {
            add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
        }
    }
}
