// seatwave-files (Module 29). Versions live in gradle/libs.versions.toml (checked on 12 November 2026);
// transitive versions are locked in gradle.lockfile.
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The teaching programs stay in src/main and need only the JDK. Everything that talks to a real store
// or database is the "sdk" source set, so the models never depend on a client library.
val sdk: SourceSet = sourceSets.create("sdk") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
sourceSets.test {
    compileClasspath += sdk.output
    runtimeClasspath += sdk.output
}
configurations[sdk.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations.testImplementation.get().extendsFrom(configurations[sdk.implementationConfigurationName])
configurations.testRuntimeOnly.get().extendsFrom(configurations[sdk.runtimeOnlyConfigurationName])

dependencies {
    // The AWS SDK signs real Signature Version 4 URLs and speaks the S3 API; the JDK has no S3 client.
    "sdkImplementation"(platform(libs.awssdk.bom))
    "sdkImplementation"(libs.awssdk.s3)
    // A settings file with problems is an expected failure, returned as a value (guidelines, rule 47).
    "sdkImplementation"(libs.result4j)
    // The JDK has the JDBC interfaces, not a driver that speaks PostgreSQL's protocol.
    "sdkRuntimeOnly"(libs.postgresql)
    // The SDK logs through SLF4J and needs a backend; logback.xml keeps it to warnings.
    "sdkRuntimeOnly"(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.named("check") {
    dependsOn(tasks.named("sdkClasses"))
}

// ./gradlew check needs no Docker. The tests tagged "stack" need the stack of compose.yaml;
// scripts/stack.sh test starts it, runs them, and stops it.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The programs (*Cli) are run by hand and their output is printed in the README and the module.
// The adapters to the real store and database are measured by the stack run, not by check.
val programs = listOf("**/*Cli*", "**/LabSettings*")
val stackOnly =
    programs + listOf("**/ObjectStore*", "**/StoredObjectsTable*", "**/ReconcileJob*", "**/S3UrlIssuer*", "**/StoreHttp*")
val checkedClasses = files(sourceSets.main.get().output, sdk.output)

tasks.jacocoTestReport {
    classDirectories.setFrom(checkedClasses.asFileTree.matching { exclude(stackOnly) })
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs + sdk.allSource.srcDirs)
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(checkedClasses.asFileTree.matching { exclude(stackOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register<JacocoReport>("stackCoverageReport") {
    group = "verification"
    description = "Writes the line coverage of the unit and stack test runs together."
    dependsOn(tasks.test, tasks.named("stackTest"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs + sdk.allSource.srcDirs)
    classDirectories.setFrom(checkedClasses.asFileTree.matching { exclude(programs) })
    reports {
        xml.required = true
    }
}

tasks.register<JacocoCoverageVerification>("stackCoverage") {
    group = "verification"
    description = "Checks line coverage of all code but the programs over both test runs."
    dependsOn(tasks.named("stackCoverageReport"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    classDirectories.setFrom(checkedClasses.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// One task per program, so a reader runs one command per example. The working folder is the
// project, where config/ and .secrets/ are.
fun registerProgram(taskName: String, mainClassName: String, text: String, sourceSet: SourceSet) =
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = text
        classpath = sourceSet.runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
    }

val root = "io.plainround.seatwave.files"
val main: SourceSet = sourceSets.main.get()
registerProgram("presignRun", "$root.PresignedUrlDemoCli", "Nine requests against the teaching signer", main)
registerProgram("multipartRun", "$root.MultipartDemoCli", "A 20 MB poster in parts, a retry, an abandoned upload", main)
registerProgram("integrityRun", "$root.IntegrityDemoCli", "Content addressing, a flipped bit, collision odds, parity", main)
registerProgram("costRun", "$root.StorageCostCli", "Five-year cost by policy; copies against erasure codes", main)
registerProgram("setupBuckets", "$root.SetupBucketsCli", "Command 2: the four buckets, lifecycle rules, no anonymous access", sdk)
registerProgram("ticketLinks", "$root.TicketLinksCli", "Command 3: a ticket through real signed URLs, and the refusals", sdk)
registerProgram("multipartStore", "$root.MultipartLostPartCli", "Command 4: multipart on the store, a lost part, the sweep", sdk)
registerProgram("reconcile", "$root.ReconcileCli", "Command 6: the reconciliation job against the store and PostgreSQL", sdk)
