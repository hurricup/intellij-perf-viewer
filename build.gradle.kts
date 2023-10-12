import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun properties(key: String) = providers.gradleProperty(key)

plugins {
    id("org.jetbrains.intellij") version "1.16.0"
    id("org.jetbrains.kotlin.jvm") version "1.8.22"
}

repositories {
    mavenCentral()
}

version = properties("pluginVersion").get().ifEmpty { properties("platformVersion").get() } +
        properties("pluginBranch").get().ifEmpty { properties("platformBranch").get() } +
        properties("pluginBuild").get().ifEmpty { properties("platformBuild").get() }

apply(plugin = "java")
apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "org.jetbrains.intellij")

intellij {
    pluginName.set(properties("name").get())
    type.set("IU")
    version.set(project.provider {
        properties("platformVersion").get() + properties("platformBranch").get() + properties("platformBuild").get()
    })
    updateSinceUntilBuild.set(true)
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        sourceCompatibility = properties("javaVersion").get()
        targetCompatibility = properties("javaTargetVersion").get()
    }

    withType<KotlinCompile>{
        kotlinOptions.jvmTarget = properties("javaTargetVersion").get()
    }

    patchPluginXml {
        changeNotes.set(provider { file(properties("pluginChangesFile").get()).readText() })
        pluginDescription.set(properties("pluginDescription").get())
    }

    test {
        systemProperty("idea.plugins.path", project.rootDir.canonicalPath + "/.test-plugins")

        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    publishPlugin {
        if (project.hasProperty("eap")) {
            channels.set(listOf("EAP"))
        }
        token.set(properties("jbToken").orElse(""))
    }
}
