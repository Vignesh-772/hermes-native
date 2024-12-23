import de.undercouch.gradle.tasks.download.Download
import java.io.FileOutputStream
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.download)
}

android {
    namespace = "in.vicky.hermesintegration"
    compileSdk = 34

    defaultConfig {
        applicationId = "in.vicky.hermesintegration"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk { abiFilters.addAll(reactNativeArchitectures()) }
        externalNativeBuild {
            cmake {
                arguments(
                    "--log-level=ERROR",
                    "-Wno-dev",
                    "-DHERMES_IS_ANDROID=True",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PIE=True",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DIMPORT_HERMESC=${File(hermesBuildDir, "ImportHermesc.cmake").toString()}",
                    "-DJSI_DIR=${jsiDir}",
                    "-DHERMES_SLOW_DEBUG=False",
                    "-DHERMES_BUILD_SHARED_JSI=True",
                    "-DHERMES_RELEASE_VERSION=for RN ${version}",
                    // We intentionally build Hermes with Intl support only. This is to simplify
                    // the build setup and to avoid overcomplicating the build-type matrix.
                    "-DHERMES_ENABLE_INTL=True")

                targets("libhermes")
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }


    // Used to override the NDK path/version on internal CI or by allowing
    // users to customize the NDK path/version from their root project (e.g. for Apple Silicon
    // support)
    if (rootProject.hasProperty("ndkPath") && rootProject.properties["ndkPath"] != null) {
        ndkPath = rootProject.properties["ndkPath"].toString()
    }
    if (rootProject.hasProperty("ndkVersion") && rootProject.properties["ndkVersion"] != null) {
        ndkVersion = rootProject.properties["ndkVersion"].toString()
    } else {
        ndkVersion = libs.versions.ndkVersion.get()
    }

    externalNativeBuild {
        cmake {
            version = cmakeVersion
            path = File("$hermesDir/CMakeLists.txt")
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // JS developers aren't VM developers.
                    // Therefore we're passing as build type Release, to provide a faster build.
                    // This has the (unlucky) side effect of letting AGP call the build
                    // tasks `configureCMakeRelease` while is actually building the debug flavor.
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DCMAKE_BUILD_TYPE=MinSizeRel",
                        // For release builds, we don't want to enable the Hermes Debugger.
                        "-DHERMES_ENABLE_DEBUGGER=False")
                }
            }
        }
    }

    sourceSets.getByName("main") {
        manifest.srcFile("$hermesDir/android/hermes/src/main/AndroidManifest.xml")
        java.srcDir("$hermesDir/lib/Platform/Intl/java")
    }

    dependencies {
        implementation(libs.fbjni)
        implementation(libs.androidx.annotation)
    }

}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

val cmakeVersion = "3.31.1"
val cmakePath = "${getSDKPath()}/cmake/$cmakeVersion"
val cmakeBinaryPath = "${cmakePath}/bin/cmake"

fun getSDKPath(): String {
    val androidSdkRoot = System.getenv("ANDROID_SDK_ROOT")
    val androidHome = "/home/vickydevil/Android/Sdk"
    return when {
        !androidSdkRoot.isNullOrBlank() -> androidSdkRoot
        !androidHome.isNullOrBlank() -> androidHome
        else -> throw IllegalStateException("Neither ANDROID_SDK_ROOT nor ANDROID_HOME is set. " + System.getenv().toString())
    }
}

fun getSDKManagerPath(): String {
    val metaSdkManagerPath = File("${getSDKPath()}/cmdline-tools/latest/bin/sdkmanager")
    val ossSdkManagerPath = File("${getSDKPath()}/tools/bin/sdkmanager")
    val windowsMetaSdkManagerPath = File("${getSDKPath()}/cmdline-tools/latest/bin/sdkmanager.bat")
    val windowsOssSdkManagerPath = File("${getSDKPath()}/tools/bin/sdkmanager.bat")
    return when {
        metaSdkManagerPath.exists() -> metaSdkManagerPath.absolutePath
        windowsMetaSdkManagerPath.exists() -> windowsMetaSdkManagerPath.absolutePath
        ossSdkManagerPath.exists() -> ossSdkManagerPath.absolutePath
        windowsOssSdkManagerPath.exists() -> windowsOssSdkManagerPath.absolutePath
        else -> throw GradleException("Could not find sdkmanager executable.")
    }
}

val reactNativeRootDir = "./"
val customDownloadDir = System.getenv("REACT_NATIVE_DOWNLOADS_DIR")
val downloadsDir =
    if (customDownloadDir != null) {
        File(customDownloadDir)
    } else {
        File(reactNativeRootDir, "sdks/download")
    }

// By default we are going to download and unzip hermes inside the /sdks/hermes folder
// but you can provide an override for where the hermes source code is located.
val buildDir = project.layout.buildDirectory.get().asFile
val overrideHermesDir = System.getenv("REACT_NATIVE_OVERRIDE_HERMES_DIR") != null
val hermesDir =
    if (overrideHermesDir) {
        File(System.getenv("REACT_NATIVE_OVERRIDE_HERMES_DIR"))
    } else {
        File(reactNativeRootDir, "sdks/hermes")
    }
val hermesBuildDir = File("$buildDir/hermes")
val hermesCOutputBinary = File("$buildDir/hermes/bin/hermesc")

// This filetree represents the file of the Hermes build that we want as input/output
// of the buildHermesC task. Gradle will compute the hash of files in the file tree
// and won't rebuilt hermesc unless those files are changing.
val hermesBuildOutputFileTree =
    fileTree(hermesBuildDir.toString())
        .include("**/*.cmake", "**/*.marks", "**/compiler_depends.ts", "**/Makefile", "**/link.txt")

var hermesVersion = "main"
val hermesVersionFile = File(reactNativeRootDir, "sdks/.hermesversion")

if (hermesVersionFile.exists()) {
    hermesVersion = hermesVersionFile.readText()
}

val ndkBuildJobs = Runtime.getRuntime().availableProcessors().toString()
val prefabHeadersDir = File("$buildDir/prefab-headers")

// We inject the JSI directory used inside the Hermes build with the -DJSI_DIR config.
val jsiDir = File(reactNativeRootDir, "ReactCommon/jsi")

val downloadHermes by
tasks.creating(Download::class) {
    src("https://github.com/facebook/hermes/tarball/${hermesVersion}")
    onlyIfModified(true)
    overwrite(true)
    quiet(true)
    useETag("all")
    retries(5)
    dest(File(downloadsDir, "hermes.tar.gz"))
}

val unzipHermes by
tasks.registering(Copy::class) {
    dependsOn(downloadHermes)
    from(tarTree(downloadHermes.dest)) {
        eachFile {
            // We flatten the unzip as the tarball contains a `facebook-hermes-<SHA>`
            // folder at the top level.
            if (this.path.startsWith("facebook-hermes-")) {
                this.path = this.path.substringAfter("/")
            }
        }
    }
    into(hermesDir)
}

// NOTE: ideally, we would like CMake to be installed automatically by the `externalNativeBuild`
// below. To do that, we would need the various `ConfigureCMake*` tasks to run *before*
// `configureBuildForHermes` and `buildHermesC` so that CMake is available for their run. But the
// `ConfigureCMake*` tasks depend upon the `ImportHermesc.cmake` file which is actually generated by
// the two tasks mentioned before, so we install CMake manually to break the circular dependency.

val installCMake by
tasks.registering(Exec::class) {
    onlyIf { !File(cmakePath).exists() }
    commandLine(
        windowsAwareCommandLine(getSDKManagerPath(), "--install", "cmake;${cmakeVersion}"))
}

val configureBuildForHermes by
tasks.registering(Exec::class) {
    dependsOn(installCMake)
    workingDir(hermesDir)
    inputs.dir(hermesDir)
    outputs.files(hermesBuildOutputFileTree)
    commandLine(
        windowsAwareCommandLine(
            cmakeBinaryPath,
            // Suppress all warnings as this is the Hermes build and we can't fix them.
            "--log-level=ERROR",
            "-Wno-dev",
            if (Os.isFamily(Os.FAMILY_WINDOWS)) "-GNMake Makefiles" else "",
            "-S",
            ".",
            "-B",
            hermesBuildDir.toString(),
            "-DJSI_DIR=" + jsiDir.absolutePath,
        ))
    standardOutput = FileOutputStream("$buildDir/configure-hermesc.log")
}

val buildHermesC by
tasks.registering(Exec::class) {
    dependsOn(configureBuildForHermes)
    workingDir(hermesDir)
    inputs.files(hermesBuildOutputFileTree)
    outputs.file(hermesCOutputBinary)
    commandLine(
        cmakeBinaryPath,
        "--build",
        hermesBuildDir.toString(),
        "--target",
        "hermesc",
        "-j",
        ndkBuildJobs,
    )
    standardOutput = FileOutputStream("$buildDir/build-hermesc.log")
    errorOutput = FileOutputStream("$buildDir/build-hermesc.error.log")
}

val prepareHeadersForPrefab by
tasks.registering(Copy::class) {
    dependsOn(buildHermesC)
    from("$hermesDir/API")
    from("$hermesDir/public")
    include("**/*.h")
    exclude("jsi/**")
    into(prefabHeadersDir)
}

fun windowsAwareCommandLine(vararg commands: String): List<String> {
    val result =
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            mutableListOf("cmd", "/c")
        } else {
            mutableListOf()
        }
    result.addAll(commands)
    return result
}

fun reactNativeArchitectures(): List<String> {
    val value = project.properties["reactNativeArchitectures"]
    return value?.toString()?.split(",") ?: listOf("armeabi-v7a", "x86", "x86_64", "arm64-v8a")
}

afterEvaluate {
    if (!overrideHermesDir) {
        // If you're not specifying a Hermes Path override, we want to
        // download/unzip Hermes from Github then.
        tasks.getByName("configureBuildForHermes").dependsOn(unzipHermes)
        tasks.getByName("prepareHeadersForPrefab").dependsOn(unzipHermes)
    }
    tasks.getByName("preBuild").dependsOn(buildHermesC)
    tasks.getByName("preBuild").dependsOn(prepareHeadersForPrefab)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation,unchecked")
    options.compilerArgs.add("-Werror")
}