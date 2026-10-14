plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "io.github.zdcache"
version = "0.2.0"
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
    testImplementation(libs.guava)
    testImplementation(libs.cache2k.api)
    testRuntimeOnly(libs.cache2k.core)
    testImplementation(libs.cqengine)
    testImplementation(libs.jackson.databind)
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

val jmh by sourceSets.creating {
    compileClasspath += sourceSets.test.get().output + sourceSets.test.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.test.get().runtimeClasspath
}

dependencies {
    "jmhImplementation"(libs.jmh.core)
    "jmhImplementation"(libs.jol.core)
    "jmhAnnotationProcessor"(libs.jmh.generator)
}

tasks.register<JavaExec>("jmh") {
    description = "Runs the JMH benchmarks against Caffeine; pass JMH options with -Pjmh.args=\"...\"."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    val results = layout.buildDirectory.file("reports/jmh/results.json")
    outputs.file(results)
    outputs.upToDateWhen { false }
    val extra = providers.gradleProperty("jmh.args").map { it.split(" ").filter(String::isNotBlank) }.orElse(listOf())
    argumentProviders.add(CommandLineArgumentProvider {
        extra.get() + listOf("-rf", "json", "-rff", results.get().asFile.absolutePath)
    })
    doFirst { results.get().asFile.parentFile.mkdirs() }
}

tasks.register<JavaExec>("footprint") {
    description = "Measures heap bytes per cached entry for Caffeine and ZDCache."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass = "io.github.zdcache.bench.MemoryFootprint"
    maxHeapSize = "4g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-Djol.magicFieldOffset=true")
    val report = layout.buildDirectory.file("reports/footprint/results.md")
    outputs.file(report)
    outputs.upToDateWhen { false }
    val plan = benchmarkPlan.get()
    inputs.file(plan)
    argumentProviders.add(CommandLineArgumentProvider { listOf(report.get().asFile.absolutePath, plan.asFile.absolutePath) })
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

val benchmarkPlan = providers.gradleProperty("benchmark.plan").orElse("full")
    .map { layout.projectDirectory.file("src/jmh/resources/benchmark-$it.properties") }

tasks.register<JavaExec>("benchmarkPlan") {
    description = "Runs every JMH benchmark with src/jmh/resources/benchmark-<plan>.properties; choose -Pbenchmark.plan=full or quick."
    group = "verification"
    classpath = jmh.runtimeClasspath
    mainClass = "io.github.zdcache.bench.BenchmarkRunner"
    val plan = benchmarkPlan.get()
    val results = layout.buildDirectory.file("reports/jmh/results.json")
    inputs.file(plan)
    outputs.file(results)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(plan.asFile.absolutePath, results.get().asFile.absolutePath)
    })
}

tasks.register<JavaExec>("benchmarkReport") {
    description = "Builds the interactive benchmark report from the last JMH and footprint results."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.zdcache.bench.BenchmarkReport"
    mustRunAfter("jmh", "benchmarkPlan", "footprint")
    val jmhResults = layout.buildDirectory.file("reports/jmh/results.json")
    val footprintResults = layout.buildDirectory.file("reports/footprint/results.json")
    val output = layout.buildDirectory.dir("reports/zdcache-benchmark")
    val mode = providers.gradleProperty("benchmark.mode").orElse("custom")
    outputs.dir(output)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(jmhResults.get().asFile.absolutePath, footprintResults.get().asFile.absolutePath, mode.get(),
            output.get().asFile.absolutePath)
    })
}

abstract class PublishBenchmarkReport : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val summary: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runMode: RegularFileProperty

    @get:OutputFile
    abstract val publishedReport: RegularFileProperty

    @get:Internal
    abstract val readme: RegularFileProperty

    @TaskAction
    fun publish() {
        val mode = runMode.get().asFile.readText().trim()
        if (mode != "full") {
            throw GradleException(
                "Only a full benchmark run can be published, but the last run was a $mode run. Run ./benchmark -full first.")
        }
        val target = publishedReport.get().asFile
        target.parentFile.mkdirs()
        report.get().asFile.copyTo(target, overwrite = true)

        val start = "<!-- benchmark-summary:start -->"
        val end = "<!-- benchmark-summary:end -->"
        val readmeFile = readme.get().asFile
        val text = readmeFile.readText()
        val from = text.indexOf(start)
        val to = text.indexOf(end)
        require(from >= 0 && to > from) { "README.md must contain $start and $end" }
        val table = summary.get().asFile.readText().trim()
        readmeFile.writeText(text.substring(0, from + start.length) + "\n" + table + "\n\n" + text.substring(to))
    }
}

tasks.register<PublishBenchmarkReport>("publishBenchmarkReport") {
    description = "Copies the last benchmark report to docs/benchmark and updates the summary in README.md."
    group = "documentation"
    report = layout.buildDirectory.file("reports/zdcache-benchmark/index.html")
    summary = layout.buildDirectory.file("reports/zdcache-benchmark/summary.md")
    runMode = layout.buildDirectory.file("reports/zdcache-benchmark/run-mode.txt")
    publishedReport = layout.projectDirectory.file("docs/benchmark/index.html")
    readme = layout.projectDirectory.file("README.md")
    outputs.upToDateWhen { false }
}
