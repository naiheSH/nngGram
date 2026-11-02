import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.nucleus)
}

// Nucleus patches Mach-O build versions and signatures during app-image creation.
// Hash the final shipped dylibs, then seal the updated manifest into the app signature.
if (System.getProperty("os.name").startsWith("Mac")) {
    val finalizer = rootProject.file("scripts/finalize-macos-app.py")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/nngGram.app")
    val finalizeMacosApp by tasks.registering(Exec::class) {
        dependsOn("createDistributable")
        commandLine("python3", finalizer.absolutePath, appImage.get().asFile.absolutePath)
    }
    tasks.matching { it.name == "packageDmg" }.configureEach {
        dependsOn(finalizeMacosApp)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.graalvm.runtime)
    implementation(libs.nucleus.decorated.window.tao)
    implementation(libs.jna)
    implementation(libs.miuix.ui)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(libs.compose.uiToolingPreview)
    testImplementation(libs.kotlin.test)
}

// Hot Reload copies runtime JARs into build/run without their adjacent DLLs.
// Apply to JavaExec so IDE-injected hotRun/MainKt tasks get the same location
// as ordinary Gradle run tasks. Explicit local overrides retain precedence.
val desktopNativeProfile: String by rootProject.extra
val developmentNativeDirectory = rootProject.file("native/$desktopNativeProfile").absolutePath
val developmentTdlibDirectory = providers.systemProperty("nnggram.tdlib.dir")
    .orElse(providers.environmentVariable("NNGGRAM_TDLIB_DIR"))
    .orElse(developmentNativeDirectory)
tasks.withType<JavaExec>().configureEach {
    systemProperty("nnggram.tdlib.dir", developmentTdlibDirectory.get())
}

nucleus {
    application {
        mainClass = "com.badnng.moe.MainKt"

        // TDLib discovers generated Java types from JNI. Keep their exact names/members;
        // Native Image performs closed-world elimination after JNI metadata is applied.
        buildTypes.release.proguard.isEnabled = false
        graalvm {
            isEnabled = true
            imageName = "nngGram"
            march = NativeImageMarch.COMPATIBILITY
            optimization = NativeImageOptimization.SIZE
            // JNA reads native.encoding (GBK on Chinese Windows), independently of UTF-8 UI text.
            allCharsets = true
            buildArgs.add("-H:-IncludeMethodData")
            providers.gradleProperty("nativeImageHeap").orNull?.let { buildArgs.add("-J-Xmx$it") }
            providers.gradleProperty("nativeImageThreads").orNull?.let { buildArgs.add("--parallelism=$it") }
        }

        nativeDistributions {
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("native"))
            // Required by the desktop dependencies; verified with suggestRuntimeModules.
            modules("java.instrument", "jdk.unsupported", "jdk.charsets", "jdk.localedata")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "nngGram"
            packageVersion = "0.1.0"
            description = "Cross-platform Telegram desktop client"
            vendor = "nngGram contributors"
            homepage = "https://github.com/naiheSH/nngGram"
            macOS {
                iconFile.set(project.file("src/main/resources/branding/nnggram.icns"))
                // jpackage rejects CFBundleShortVersionString with a zero major version.
                packageVersion = "1.0.0"
                bundleID = "com.badnng.nnggram"
                infoPlist {
                    extraKeysRawXml = "<key>NSMicrophoneUsageDescription</key><string>录制并发送语音消息</string>"
                }
            }
            linux {
                iconFile.set(project.file("src/main/resources/branding/nnggram.png"))
                packageName = "nnggram"
                debMaintainer = "naiheSH <121236589+naiheSH@users.noreply.github.com>"
                appCategory = "Network"
                debDepends = listOf("ffmpeg", "libssl3t64", "zlib1g", "libasound2t64", "libgl1", "libegl1", "libfontconfig1")
            }
            windows {
                iconFile.set(project.file("src/main/resources/branding/nnggram.ico"))
            }
        }
    }
}

// Nucleus 2.5.18's Windows Native Image packaging copies Skiko but leaves Tao/ANGLE
// inside dependency JARs. Ship the exact Windows x64 libraries beside Skiko so
// ANGLE's module-relative libGLESv2 lookup works without a per-user extraction cache.
val copyGraalvmWindowsRuntimeLibraries by tasks.registering(Copy::class) {
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { files ->
        files.filter { it.extension == "jar" }.map { zipTree(it) }
    }) {
        include("nucleus/native/win32-x64/*.dll", "com/sun/jna/win32-x86-64/jnidispatch.dll")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(layout.buildDirectory.dir("compose/binaries/main/graalvm-app/nngGram/bin"))
}
tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    dependsOn(copyGraalvmWindowsRuntimeLibraries)
}

// Nucleus 2.5.18 only searches Program Files for rc.exe. Portable SDKs keep their
// own location: retain Nucleus's generated icon/version/DPI manifest, then compile
// that exact resource script with the explicitly selected SDK if discovery fails.
val portableWindowsRc = providers.gradleProperty("windowsRc")
if (portableWindowsRc.isPresent) {
    afterEvaluate {
        tasks.named("generateGraalvmWindowsResources").configure {
            inputs.file(portableWindowsRc)
            val nucleusActions = actions.toList()
            actions.clear()
            doLast {
                try {
                    nucleusActions.forEach { it.execute(this) }
                } catch (failure: IllegalStateException) {
                    if (failure.message != "Could not locate rc.exe from Windows SDK. Ensure Windows SDK is installed.") throw failure
                    val resource = outputs.files.singleFile
                    val source = File(resource.parentFile, "icon.rc")
                    check(source.isFile) { "Nucleus did not generate the Windows resource script." }
                    val result = ProcessBuilder(portableWindowsRc.get(), "/fo", resource.absolutePath, source.absolutePath)
                        .inheritIO().start().waitFor()
                    check(result == 0 && resource.isFile) { "Windows resource compiler failed ($result)." }
                }
            }
        }
    }
}
