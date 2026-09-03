plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
    `java-library`
}

group = "io.github.nandishn"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

description = "DynamoDB and S3 checkpoint persistence provider for Koog agents."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    api(libs.koog.snapshot)
    api(libs.aws.dynamodb)
    api(libs.aws.s3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)

    add("integrationTestImplementation", libs.testcontainers.junit.jupiter)
    add("integrationTestImplementation", libs.testcontainers.localstack)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "io.github.nandishn.koog.checkpoint.aws"
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("NOTICE")) {
        into("META-INF")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against LocalStack or real AWS-compatible endpoints."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
    onlyIf {
        providers.environmentVariable("KOOG_AWS_INTEGRATION_TESTS")
            .map(String::toBoolean)
            .orElse(false)
            .get()
    }
}

tasks.check {
    dependsOn(tasks.test, tasks.named("compileIntegrationTestKotlin"))
}

mavenPublishing {
    coordinates(project.group.toString(), "koog-checkpoint-dynamodb-s3", project.version.toString())
    publishToMavenCentral()
    if (providers.gradleProperty("signAllPublications").map(String::toBoolean).orElse(false).get()) {
        signAllPublications()
    }

    pom {
        name.set("koog-checkpoint-dynamodb-s3")
        description.set(project.description)
        url.set("https://github.com/nandishn/koog-checkpoint-dynamodb-s3")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("nandishn")
                name.set("Nandish")
                url.set("https://github.com/nandishn")
            }
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/nandishn/koog-checkpoint-dynamodb-s3/issues")
        }
        scm {
            connection.set("scm:git:https://github.com/nandishn/koog-checkpoint-dynamodb-s3.git")
            developerConnection.set("scm:git:ssh://git@github.com/nandishn/koog-checkpoint-dynamodb-s3.git")
            url.set("https://github.com/nandishn/koog-checkpoint-dynamodb-s3")
        }
    }
}
