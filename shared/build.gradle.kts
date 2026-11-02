import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Application identity is supplied by the builder, never checked into source control.
val telegramApiSources = layout.buildDirectory.dir("generated/telegramApi/kotlin")
val generateTelegramApi by tasks.registering {
    notCompatibleWithConfigurationCache("Application identity generation must not serialize credential providers into the configuration cache.")
    val credentialsFile = rootProject.layout.projectDirectory.file("telegram-api.local.properties")
    val envId = providers.environmentVariable("NNGGRAM_API_ID")
    val envHash = providers.environmentVariable("NNGGRAM_API_HASH")
    val required = providers.gradleProperty("requireTelegramApi").map { it.toBoolean() }.orElse(false)
    outputs.dir(telegramApiSources)
    // Do not put secrets into task input fingerprints or share generated source through caches.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doLast {
        val local = Properties()
        if (credentialsFile.asFile.isFile) credentialsFile.asFile.reader(Charsets.UTF_8).use(local::load)
        val useEnvironment = envId.isPresent || envHash.isPresent
        val id = (if (useEnvironment) envId.orNull else local.getProperty("api_id")).orEmpty().trim()
        val hash = (if (useEnvironment) envHash.orNull else local.getProperty("api_hash")).orEmpty().trim()
        val present = id.isNotEmpty() || hash.isNotEmpty()
        check(!required.get() || present) { "Telegram API credentials are required; see README.md." }
        check(!present || (id.toIntOrNull()?.let { it > 0 } == true && hash.matches(Regex("[a-fA-F0-9]{32}")))) {
            "Provide a complete valid Telegram API ID/Hash pair; see README.md."
        }
        val target = telegramApiSources.get().file("com/badnng/moe/tdlib/BundledTelegramApi.kt").asFile
        target.parentFile.mkdirs()
        val source = "package com.badnng.moe.tdlib\n\ninternal object BundledTelegramApi {\n" +
            "    const val ID: String = \"${if (present) id.toInt().toString() else ""}\"\n" +
            "    const val HASH: String = \"$hash\"\n}\n"
        if (!target.isFile || target.readText() != source) target.writeText(source)
    }
}

val desktopNativeProfile: String by rootProject.extra
val hostTdlibBundle = "native/$desktopNativeProfile/tdlib.jar"

kotlin {
    jvm()


    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.blur)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.squircle)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.filekit.dialogs)
            implementation(libs.jna)
            implementation(libs.bouncycastle.bcprov)
            implementation(files(rootProject.file(hostTdlibBundle)))
            implementation(libs.kotlinx.coroutinesCore)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            runtimeOnly(compose.desktop.currentOs)
        }
    }
}

kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(telegramApiSources) }
tasks.named("compileKotlinJvm") { dependsOn(generateTelegramApi) }

tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.file("native/tdlib-source.properties"))
}

val verifyTdlibBindings by tasks.registering {
    val binding = rootProject.layout.projectDirectory.file(hostTdlibBundle)
    val windows = System.getProperty("os.name").startsWith("Windows")
    doLast {
        check(binding.asFile.isFile) {
            "Missing $hostTdlibBundle. Run scripts/build-tdlib.${if (windows) "ps1" else "sh"} with JDK 21 first."
        }
    }
}
tasks.named("compileKotlinJvm") { dependsOn(verifyTdlibBindings) }

val hostTdlibMetadata = layout.buildDirectory.dir("generated/hostTdlibMetadata")
val generateHostTdlibMetadata by tasks.registering(Exec::class) {
    dependsOn(verifyTdlibBindings)
    inputs.file(rootProject.file(hostTdlibBundle))
    inputs.file(rootProject.file("native/tdlib-source.properties"))
    inputs.file(rootProject.file("scripts/GenerateTdlibNativeMetadata.java"))
    outputs.dir(hostTdlibMetadata)
    commandLine(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        rootProject.file("scripts/GenerateTdlibNativeMetadata.java").absolutePath,
        rootProject.projectDir.absolutePath,
        "--binding=$hostTdlibBundle", "--output=${hostTdlibMetadata.get().asFile.absolutePath}",
    )
}
val originalTdlibResources = file("src/jvmMain/resources").toPath()
tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(generateHostTdlibMetadata)
    from(hostTdlibMetadata)
    eachFile {
        if ((path == "tdlib-native-image.properties" || path.startsWith("META-INF/native-image/com.badnng.moe/tdlib/")) &&
            file.toPath().startsWith(originalTdlibResources)) exclude()
    }
}

val verifyTdlibNativeMetadata by tasks.registering(Exec::class) {
    dependsOn(verifyTdlibBindings)
    val binding = rootProject.layout.projectDirectory.file(hostTdlibBundle)
    onlyIf { binding.asFile.isFile && System.getProperty("os.name").startsWith("Windows") }
    inputs.file(rootProject.file("native/tdlib-source.properties"))
    inputs.file(rootProject.file("scripts/GenerateTdlibNativeMetadata.java"))
    inputs.dir("src/jvmMain/resources/META-INF/native-image/com.badnng.moe/tdlib")
    inputs.file("src/jvmMain/resources/tdlib-native-image.properties")
    val checkerTmp = layout.buildDirectory.dir("metadata-check-tmp")
    doFirst { checkerTmp.get().asFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/java").absolutePath,
        "-Djava.io.tmpdir=${checkerTmp.get().asFile.absolutePath}",
        rootProject.file("scripts/GenerateTdlibNativeMetadata.java").absolutePath,
        rootProject.projectDir.absolutePath,
        "--check",
    )
}
tasks.named("jvmProcessResources") { dependsOn(verifyTdlibNativeMetadata) }

tasks.withType<Test>().configureEach {
    // Separate JVMs isolate UI globals while keeping the full suite practical on CI.
    maxParallelForks = providers.gradleProperty("testForks").orNull?.toIntOrNull()?.coerceIn(1, 4) ?: 2
    val temporaryDirectory = layout.buildDirectory.dir("test-tmp")
    doFirst { temporaryDirectory.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", temporaryDirectory.get().asFile.absolutePath)
    if (!providers.gradleProperty("nativeSmoke").isPresent) exclude("**/NativeSmokeTest*")
    if (providers.gradleProperty("mediaSmoke").isPresent) {
        val folder = hostTdlibBundle.removeSuffix("/tdlib.jar")
        val tool = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
        systemProperty("nnggram.ffmpeg.path", rootProject.file("$folder/media/$tool").absolutePath)
    } else {
        exclude("**/MediaDecoderSmokeTest*")
        exclude("**/InlineVideoPlayerSmokeTest*")
    }
    if (providers.gradleProperty("nativeAcceptance").isPresent) {
        systemProperty("nnggram.acceptance.native", rootProject.file("native/windows-x64").absolutePath)
        doFirst { systemProperty("nnggram.acceptance.classpath", classpath.asPath) }
    } else {
        exclude("**/NativeAcceptanceTest*")
    }
}
