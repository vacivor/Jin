plugins {
    id("java-library")
    id("maven-publish")
}

open class JinExtension {
    var runtime: String = "undertow"
}

group = "io.jin"
version = providers.gradleProperty("jin.version")
    .orElse("1.0-SNAPSHOT")
    .get()

val jinJavaVersion: Int = providers.gradleProperty("jin.javaVersion")
    .map { it.toInt() }
    .orElse(JavaVersion.current().majorVersion.toInt())
    .get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jinJavaVersion))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

extensions.create("jin", JinExtension::class.java)

val jinRuntime: String = providers.gradleProperty("jin.runtime")
    .orElse(providers.provider { extensions.getByType(JinExtension::class.java).runtime })
    .get()
    .trim()
    .lowercase()

require(jinRuntime in setOf("netty", "undertow", "tomcat")) {
    "Invalid jin.runtime=$jinRuntime, supported values: netty, undertow, tomcat"
}

sourceSets {
    main {
        java {
            if (jinRuntime != "netty") {
                exclude("io/jin/web/adapter/NettyJinServer.java")
            }
            if (jinRuntime != "undertow") {
                exclude("io/jin/web/adapter/UndertowJinServer.java")
            }
            if (jinRuntime != "tomcat") {
                exclude("io/jin/web/adapter/TomcatJinServer.java")
            }
            if (jinRuntime != "tomcat") {
                exclude("io/jin/web/adapter/JinServlet.java")
                exclude("io/jin/web/adapter/JinDispatcherServlet.java")
            }
        }
    }
}

dependencies {
    if (jinRuntime == "tomcat") {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:10.1.34")
    }
    if (jinRuntime == "netty") {
        implementation("io.netty:netty-all:4.1.117.Final")
    }
    if (jinRuntime == "undertow") {
        implementation("io.undertow:undertow-core:2.3.18.Final")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.glassfish.jersey.core:jersey-common:3.1.9")

    if (jinRuntime == "tomcat") {
        compileOnly("jakarta.servlet:jakarta.servlet-api:5.0.0")
    }
    api("jakarta.inject:jakarta.inject-api:2.0.1")
    api("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "jin-web"
            version = project.version.toString()
            pom {
                name.set("jin-web")
                description.set("Jin lightweight web framework")
            }
        }
    }
    repositories {
        maven {
            val releasesRepo = providers.gradleProperty("nexusReleasesUrl")
                .orElse("https://nexus.281018.xyz/repository/maven-releases/")
                .get()
            val snapshotsRepo = providers.gradleProperty("nexusSnapshotsUrl")
                .orElse("https://nexus.281018.xyz/repository/maven-snapshots/")
                .get()
            url = uri(if (project.version.toString().endsWith("SNAPSHOT")) snapshotsRepo else releasesRepo)
            val nexusUsername = (findProperty("nexusUsername") as String?) ?: System.getenv("NEXUS_USERNAME")
            val nexusPassword = (findProperty("nexusPassword") as String?) ?: System.getenv("NEXUS_PASSWORD")
            if (!nexusUsername.isNullOrBlank() || !nexusPassword.isNullOrBlank()) {
                credentials {
                    username = nexusUsername
                    password = nexusPassword
                }
            }
        }
    }
}
