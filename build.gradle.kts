import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("fabric-loom") version "1.9.1"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("storyclient") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
    mavenCentral()
    maven { url = uri("https://maven.shedaniel.me/") }
    maven { url = uri("https://maven.terraformersmc.com/releases/") }
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    modImplementation("net.kyori:adventure-platform-fabric:5.14.1")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modApi("me.shedaniel.cloth:cloth-config-fabric:15.0.140")
    modApi("com.terraformersmc:modmenu:11.0.3")

    // Add MP3 support
    implementation("javazoom:jlayer:1.0.1")

    // Dear ImGui (Java bindings) for the DM Control Panel overlay.
    // Use the same approach as Axiom: bundle binding + lwjgl3 backend + all-OS natives,
    // and shade them into the mod jar via `include` so end-users don't need to install them.
    // Earliest imgui-java release with a macOS-arm64 native (Apple Silicon).
    // Axiom bundles 1.86.11 — but that's x86_64-only on macOS and crashes on M-series chips.
    // 1.87.7 is the first universal2 build. Targets LWJGL 3.3.4 transitively, compatible
    // with Minecraft 1.21.1's bundled LWJGL 3.3.3 (we exclude the transitive below
    // so MC owns the LWJGL version).
    val imguiVersion = "1.87.7"
    val imguiBinding = "io.github.spair:imgui-java-binding:$imguiVersion"
    val imguiLwjgl3 = "io.github.spair:imgui-java-lwjgl3:$imguiVersion"
    val imguiWin = "io.github.spair:imgui-java-natives-windows:$imguiVersion"
    val imguiLinux = "io.github.spair:imgui-java-natives-linux:$imguiVersion"
    val imguiMac = "io.github.spair:imgui-java-natives-macos:$imguiVersion"
    // Exclude LWJGL transitives — Minecraft owns the LWJGL version on the classpath.
    val excludeLwjgl: ExternalModuleDependency.() -> Unit = {
        exclude(group = "org.lwjgl")
    }
    implementation(imguiBinding, excludeLwjgl); include(imguiBinding)
    implementation(imguiLwjgl3,  excludeLwjgl); include(imguiLwjgl3)
    runtimeOnly(imguiWin,    excludeLwjgl);     include(imguiWin)
    runtimeOnly(imguiLinux,  excludeLwjgl);     include(imguiLinux)
    runtimeOnly(imguiMac,    excludeLwjgl);     include(imguiMac)

    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version"),
            "loader_version" to project.property("loader_version"),
            "kotlin_loader_version" to project.property("kotlin_loader_version")
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}

// Build the remapped jar and deploy it into the Prism Launcher "Story" instance's mods folder.
// Override the target with -Pprism_mods_dir=/path/to/mods (e.g. for a different instance).
val deployToPrism by tasks.registering(Copy::class) {
    group = "story"
    description = "Builds the remapped jar and copies it into the Prism Launcher Story instance mods folder, replacing the previous build."

    // remapJar is the deployable, Loom-remapped artifact (what `build` produces in build/libs).
    val remapJar = tasks.named<org.gradle.jvm.tasks.Jar>("remapJar")
    dependsOn(remapJar)

    val defaultModsDir = "${System.getProperty("user.home")}/Library/Application Support/PrismLauncher/instances/Story/.minecraft/mods"
    val modsDir = (project.findProperty("prism_mods_dir") as String?) ?: defaultModsDir
    val baseName = base.archivesName.get()

    val jarFile = remapJar.flatMap { it.archiveFile }
    val jarName = remapJar.flatMap { it.archiveFileName }

    from(jarFile)
    into(modsDir)

    doFirst {
        val dir = file(modsDir)
        if (!dir.isDirectory) {
            throw GradleException("Prism mods dir not found: $modsDir\nPass -Pprism_mods_dir=/path/to/mods to override.")
        }
        // Remove previously deployed jars so a renamed/versioned build doesn't leave stale copies.
        dir.listFiles { f -> f.name.startsWith("$baseName-") && f.extension == "jar" }
            ?.forEach { it.delete() }
    }

    doLast {
        logger.lifecycle("Deployed ${jarName.get()} -> $modsDir")
    }
}
