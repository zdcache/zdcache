plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "io.github.zdcache"
version = "0.1.0-SNAPSHOT"
description = "ZDCache: an in-process, multi-type cache searchable along specification-defined dimensions."

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly(libs.caffeine)
    testImplementation(libs.caffeine)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.github.zdcache")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<JavaExec>("compareStores") {
    group = "verification"
    description = "Small non-JMH smoke comparison of both identity stores"
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("io.github.zdcache.benchmark.StoreComparison")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        name = project.name
        description = provider { project.description }
        url = "https://github.com/zdcache/zdcache"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "steinard"
                name = "Steinar Dragsnes"
                email = "steinar.dragsnes@gmail.com"
            }
        }
        scm {
            connection = "scm:git:https://github.com/zdcache/zdcache.git"
            developerConnection = "scm:git:ssh://git@github.com/zdcache/zdcache.git"
            url = "https://github.com/zdcache/zdcache"
        }
    }
}
