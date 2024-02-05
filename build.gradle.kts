import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.tasks.Jar

plugins {
	kotlin("jvm") version "1.7.21"
	kotlin("plugin.serialization") version "1.8.20"
}

val lwjglVersion = "3.3.1"

val lwjglNatives = Pair(
	System.getProperty("os.name")!!,
	System.getProperty("os.arch")!!
).let { (name, arch) ->
	when {
		arrayOf("Mac OS X", "Darwin").any { name.startsWith(it) }                -> {
			"natives-macos${if (arch.startsWith("aarch64")) "-arm64" else ""}"
		}

		else -> throw Error("Unrecognized or unsupported platform. Please set \"lwjglNatives\" manually")
	}
}


repositories {
	mavenCentral()
	mavenLocal()
}

dependencies {
	implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

	implementation("org.lwjgl", "lwjgl")
	implementation("org.lwjgl", "lwjgl-glfw")
	implementation("org.lwjgl", "lwjgl-jemalloc")
	implementation("org.lwjgl", "lwjgl-libdivide")
	implementation("org.lwjgl", "lwjgl-llvm")
	implementation("org.lwjgl", "lwjgl-lmdb")
	implementation("org.lwjgl", "lwjgl-lz4")
	implementation("org.lwjgl", "lwjgl-meow")
	implementation("org.lwjgl", "lwjgl-nfd")
	implementation("org.lwjgl", "lwjgl-openal")
	implementation("org.lwjgl", "lwjgl-opengl")
	implementation("org.lwjgl", "lwjgl-opengles")
	implementation("org.lwjgl", "lwjgl-remotery")
	implementation("org.lwjgl", "lwjgl-rpmalloc")
	implementation("org.lwjgl", "lwjgl-shaderc")
	implementation("org.lwjgl", "lwjgl-stb")
	implementation("org.lwjgl", "lwjgl-tinyfd")
	implementation("org.lwjgl", "lwjgl-vma")
	implementation("org.lwjgl", "lwjgl-vulkan")
	implementation("org.lwjgl", "lwjgl-xxhash")
	implementation("org.lwjgl", "lwjgl-zstd")
	implementation("dev.romainguy:kotlin-math:1.5.0")
	implementation("it.unimi.dsi:fastutil-core:8.5.8")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-hocon:1.5.0")
	implementation("org.fusesource.jansi:jansi:2.4.1")
	implementation("org.jline:jline:3.25.1")
	runtimeOnly("org.lwjgl", "lwjgl", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-glfw", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-jemalloc", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-libdivide", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-llvm", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-lmdb", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-lz4", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-meow", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-nfd", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-openal", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-opengl", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-opengles", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-remotery", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-rpmalloc", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-shaderc", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-stb", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-tinyfd", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-vma", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-vulkan", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-xxhash", classifier = lwjglNatives)
	runtimeOnly("org.lwjgl", "lwjgl-zstd", classifier = lwjglNatives)
	implementation(kotlin("stdlib-jdk8"))
	implementation(kotlin("reflect"))
	implementation("io.github.config4k:config4k:0.5.0")
	testImplementation(kotlin("test"))
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")

//	implementation("org.openjdk.jol:jol-core:0.16")
	implementation("io.dropwizard.metrics:metrics-core:4.2.0")

}


tasks.test {
	useJUnitPlatform()
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
	jvmTarget = "17"
	languageVersion = "1.9"
	apiVersion = "1.9"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
	jvmTarget = "17"
	languageVersion = "1.9"
	apiVersion = "1.9"
}

val fatJar = task("fatJar", type = Jar::class) {
//	arB = "${project.name}-fat"
	manifest {
		attributes["Implementation-Title"] = "Gradle Jar File Example"
		attributes["Main-Class"] = "arx.application.TuiApplicationKt"
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}
	from(configurations.runtimeClasspath.get().map {
		if (it.isDirectory) {
			it
		} else {
			zipTree(it)
		}
	})

	with(tasks.jar.get() as CopySpec)
}

tasks {
	"build" {
		dependsOn(fatJar)
	}
}