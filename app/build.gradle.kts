import com.android.build.api.variant.BuildConfigField
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun releaseSigningCred(
    prop: String,
    envSuffix: String,
): String? =
    (
        keystoreProps.getProperty(prop)
            ?: System.getenv("KNIT_SIGNING_$envSuffix")
            ?: System.getenv("KNIT_UPLOAD_$envSuffix")
    )?.takeIf { it.isNotBlank() }

val bundleRequested = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val nativeSymbols = (project.findProperty("knit.nativeSymbols") as? String)?.toBoolean() ?: bundleRequested
val slim = (project.findProperty("slim") as? String)?.toBoolean() == true

val knitVersionName: String = providers.gradleProperty("knit.versionName").get()
val knitVersionCode: Int =
    run {
        val stages = mapOf("alpha" to 0, "beta" to 1, "rc" to 2)
        val stableStage = 9
        val m =
            Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?""").matchEntire(knitVersionName)
                ?: throw GradleException("knit.versionName '$knitVersionName' is not X.Y.Z or X.Y.Z-(alpha|beta|rc).N")
        val (major, minor, patch) = (1..3).map { m.groupValues[it].toInt() }
        val stage = stages[m.groupValues[4]] ?: stableStage
        val n = m.groupValues[5].toIntOrNull() ?: 0
        if (major > 99 || minor > 99 || patch > 99 || n > 99) {
            throw GradleException("knit.versionName '$knitVersionName' is out of range: each field at most 99")
        }
        major * 10_000_000 + minor * 100_000 + patch * 1_000 + stage * 100 + n
    }.also { derived ->
        val stored = providers.gradleProperty("knit.versionCode").get()
        if (stored != derived.toString()) {
            throw GradleException("knit.versionCode=$stored but $knitVersionName derives $derived")
        }
    }
val offWhenSlim: Boolean? = if (slim) false else null

val internetPlane = (project.findProperty("internetPlane") as? String)?.toBoolean() ?: offWhenSlim
val loraPlane = (project.findProperty("loraPlane") as? String)?.toBoolean()
val commons = (project.findProperty("commons") as? String)?.toBoolean()
val devView = (project.findProperty("devView") as? String)?.toBoolean()
val spoolDirectory = (project.findProperty("spoolDirectory") as? String)?.toBoolean()
val bleSidePlane = (project.findProperty("bleSidePlane") as? String)?.toBoolean()
val bleGattPeers = (project.findProperty("bleGattPeers") as? String)?.toBoolean()
val blobQuery = (project.findProperty("blobQuery") as? String)?.toBoolean()
val bleCodedPhy = (project.findProperty("bleCodedPhy") as? String)?.toBoolean()
val bleLinkBoost = (project.findProperty("bleLinkBoost") as? String)?.toBoolean()
val bleFeedWindow = (project.findProperty("bleFeedWindow") as? String)?.toBoolean()
val wearStatus = (project.findProperty("wearStatus") as? String)?.toBoolean()
val directLinks = (project.findProperty("directLinks") as? String)?.toBoolean()

val calls = (project.findProperty("calls") as? String)?.toBoolean() ?: offWhenSlim
val liveCalls = (project.findProperty("liveCalls") as? String)?.toBoolean() ?: calls
val meshCalls = (project.findProperty("meshCalls") as? String)?.toBoolean() ?: calls
val stt = (project.findProperty("stt") as? String)?.toBoolean() ?: offWhenSlim
val knitAnywhere = (project.findProperty("knitAnywhere") as? String)?.toBoolean() ?: offWhenSlim
val liveCallsInDebug = liveCalls ?: true
val liveCallsInRelease = liveCalls ?: true
val meshCallsInDebug = meshCalls ?: true
val meshCallsInRelease = meshCalls ?: true
val callsInDebug = liveCallsInDebug || meshCallsInDebug
val callsInRelease = liveCallsInRelease || meshCallsInRelease
val sttInDebug = (stt ?: true) && callsInDebug
val sttInRelease = (stt ?: false) && callsInRelease
val translate = (project.findProperty("translate") as? String)?.toBoolean() ?: false

val debugAbis =
    ((project.findProperty("knit.debugAbis") as? String) ?: "arm64-v8a,x86_64")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val stagingAbis =
    ((project.findProperty("knit.stagingAbis") as? String).orEmpty())
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

android {
    namespace = "app.getknit.knit"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }
    buildToolsVersion = "37.0.0"

    if (nativeSymbols) ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.getknit.knit"
        minSdk = 29
        targetSdk = 36
        versionCode = knitVersionCode
        versionName = knitVersionName
        if (slim) versionNameSuffix = "-slim"

        buildConfigField("boolean", "SEED_DEMO", "false")
        buildConfigField("boolean", "DEMO_DIRECTOR", "false")
        buildConfigField("String", "DEMO_THEME", "\"hiking\"")
        buildConfigField("boolean", "INTERNET_PLANE", (internetPlane ?: true).toString())
        buildConfigField("boolean", "LORA_PLANE", (loraPlane ?: true).toString())
        buildConfigField("boolean", "COMMONS", (commons ?: true).toString())
        buildConfigField("boolean", "DEV_VIEW", (devView ?: true).toString())
        buildConfigField("boolean", "SPOOL_DIRECTORY", (spoolDirectory ?: false).toString())
        buildConfigField("boolean", "BLE_SIDE_PLANE", (bleSidePlane ?: true).toString())
        buildConfigField("boolean", "BLE_GATT_PEERS", (bleGattPeers ?: true).toString())
        buildConfigField("boolean", "BLOB_QUERY", (blobQuery ?: true).toString())
        buildConfigField("boolean", "BLE_CODED_PHY", (bleCodedPhy ?: true).toString())
        buildConfigField("boolean", "BLE_LINK_BOOST", (bleLinkBoost ?: true).toString())
        buildConfigField("boolean", "BLE_FEED_WINDOW", (bleFeedWindow ?: true).toString())
        buildConfigField("boolean", "WEAR_STATUS", (wearStatus ?: true).toString())
        buildConfigField("boolean", "DIRECT_LINKS", (directLinks ?: true).toString())
        buildConfigField("boolean", "LIVE_CALLS", liveCallsInDebug.toString())
        buildConfigField("boolean", "MESH_CALLS", meshCallsInDebug.toString())
        buildConfigField("boolean", "STT", sttInDebug.toString())
        buildConfigField("boolean", "TRANSLATE", translate.toString())
        buildConfigField("boolean", "KNIT_ANYWHERE", (knitAnywhere ?: true).toString())
        buildConfigField("String", "MODEL_FAULT_ON_LOAD", "\"\"")
        buildConfigField("String", "GIT_SHA", "\"\"")
    }

    signingConfigs {
        val store = releaseSigningCred("storeFile", "STORE_FILE")
        val storePass = releaseSigningCred("storePassword", "STORE_PASSWORD")
        val alias = releaseSigningCred("keyAlias", "KEY_ALIAS")
        val keyPass = releaseSigningCred("keyPassword", "KEY_PASSWORD")
        if (store != null && storePass != null && alias != null && keyPass != null) {
            create("release") {
                storeFile = file(store)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (nativeSymbols) {
                ndk {
                    debugSymbolLevel = "SYMBOL_TABLE"
                }
            }
            vcsInfo {
                include = false
            }
            buildConfigField("boolean", "SEED_DEMO", "false")
            buildConfigField("boolean", "DEMO_DIRECTOR", "false")
            buildConfigField("boolean", "INTERNET_PLANE", (internetPlane ?: true).toString())
            buildConfigField("boolean", "LORA_PLANE", (loraPlane ?: true).toString())
            buildConfigField("boolean", "COMMONS", (commons ?: false).toString())
            buildConfigField("boolean", "DEV_VIEW", (devView ?: false).toString())
            buildConfigField("boolean", "SPOOL_DIRECTORY", (spoolDirectory ?: false).toString())
            buildConfigField("boolean", "BLE_SIDE_PLANE", (bleSidePlane ?: true).toString())
            buildConfigField("boolean", "BLE_GATT_PEERS", (bleGattPeers ?: true).toString())
            buildConfigField("boolean", "BLOB_QUERY", (blobQuery ?: true).toString())
            buildConfigField("boolean", "BLE_CODED_PHY", (bleCodedPhy ?: true).toString())
            buildConfigField("boolean", "BLE_LINK_BOOST", (bleLinkBoost ?: true).toString())
            buildConfigField("boolean", "BLE_FEED_WINDOW", (bleFeedWindow ?: true).toString())
            buildConfigField("boolean", "WEAR_STATUS", (wearStatus ?: false).toString())
            buildConfigField("boolean", "DIRECT_LINKS", (directLinks ?: false).toString())
            buildConfigField("boolean", "LIVE_CALLS", liveCallsInRelease.toString())
            buildConfigField("boolean", "MESH_CALLS", meshCallsInRelease.toString())
            buildConfigField("boolean", "STT", sttInRelease.toString())
            buildConfigField("boolean", "TRANSLATE", translate.toString())
            buildConfigField("boolean", "KNIT_ANYWHERE", (knitAnywhere ?: true).toString())
            buildConfigField("String", "MODEL_FAULT_ON_LOAD", "\"\"")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isPseudoLocalesEnabled = true
            if (debugAbis.isNotEmpty()) {
                ndk {
                    abiFilters += debugAbis
                }
            }
        }
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            if (stagingAbis.isNotEmpty()) {
                ndk {
                    abiFilters += stagingAbis
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors =
            providers
                .gradleProperty("knit.warningsAsErrors")
                .map(String::toBoolean)
                .orElse(false)
                .get()
    }
    androidResources {
        noCompress += listOf("tflite")
        generateLocaleConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    packaging {
        jniLibs {
            if (!nativeSymbols) keepDebugSymbols += "**/*.so"
        }
    }
}

abstract class GitShortSha : ValueSource<String, GitShortSha.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String =
        runCatching {
            val repo =
                parameters.repoDir.asFile
                    .get()
                    .absolutePath
            val out = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine("git", "-C", repo, "rev-parse", "--short", "HEAD")
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            if (result.exitValue == 0) out.toString(Charsets.UTF_8).trim() else ""
        }.getOrDefault("")
}

val gitShortSha =
    providers.of(GitShortSha::class.java) {
        parameters.repoDir.set(rootProject.layout.projectDirectory)
    }

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.buildConfigFields?.put(
            "GIT_SHA",
            gitShortSha.map { BuildConfigField("String", "\"$it\"", "Short commit of the checkout; debug only") },
        )
    }
    @Suppress("UnstableApiUsage")
    onVariants { variant ->
        val debug = variant.buildType == "debug"
        if (if (debug) callsInDebug else callsInRelease) variant.sources.manifests.addStaticManifestFile("src/calls/AndroidManifest.xml")
        if (if (debug) liveCallsInDebug else liveCallsInRelease) {
            variant.sources.keepRules?.addStaticSourceDirectory(
                "src/liveCalls/keepRules",
            )
        }
        if (if (debug) sttInDebug else sttInRelease) {
            variant.sources.manifests.addStaticManifestFile("src/stt/AndroidManifest.xml")
            variant.sources.keepRules?.addStaticSourceDirectory("src/stt/keepRules")
        }
    }
}

val platformCopiesStripped: Attribute<Boolean> = Attribute.of("app.getknit.platformCopiesStripped", Boolean::class.javaObjectType)

abstract class StripPlatformCopies : TransformAction<StripPlatformCopies.Params> {
    interface Params : TransformParameters {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        val bootClasspath: ConfigurableFileCollection
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val platform =
            parameters.bootClasspath.files.flatMapTo(HashSet()) { boot ->
                ZipFile(boot).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .toList()
                }
            }
        val input = jar.get().asFile
        ZipFile(input).use { zip ->
            val entries = zip.entries().toList()
            if (entries.none { it.name in platform }) {
                outputs.file(jar)
                return
            }
            ZipOutputStream(outputs.file("${input.nameWithoutExtension}-platform-stripped.jar").outputStream().buffered()).use { out ->
                for (entry in entries.filterNot { it.name in platform }) {
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}

dependencies {
    attributesSchema { attribute(platformCopiesStripped) }
    artifactTypes.maybeCreate(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(platformCopiesStripped, false)
    registerTransform(StripPlatformCopies::class) {
        from
            .attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE,
            ).attribute(platformCopiesStripped, false)
        to
            .attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE,
            ).attribute(platformCopiesStripped, true)
        parameters.bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
    }
}

androidComponents {
    onVariants { variant -> variant.runtimeConfiguration.attributes.attribute(platformCopiesStripped, true) }
}

val checkModerationModels =
    tasks.register("checkModerationModels") {
        description = "Fails the build if a bundled .tflite moderation model is missing or a stub."
        val models =
            listOf("nsfw.tflite", "toxicity.tflite").map {
                layout.projectDirectory.file("src/main/assets/moderation/$it").asFile
            }
        val stamp = layout.buildDirectory.file("tmp/checkModerationModels.stamp")
        inputs.files(models).withPropertyName("moderationModels")
        outputs.file(stamp)
        doLast {
            models.forEach { model ->
                if (!model.isFile) {
                    throw GradleException("Moderation model missing: $model")
                }
                val head = model.inputStream().use { String(it.readNBytes(48), Charsets.US_ASCII) }
                if (head.startsWith("version https://git-lfs")) {
                    throw GradleException("Moderation model $model is a Git LFS pointer, not the real model.")
                }
                if (model.length() < 1_000_000L) {
                    throw GradleException("Moderation model $model is only ${model.length()} bytes — expected >= 1 MB.")
                }
            }
            stamp
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("ok")
        }
    }

tasks.named("preBuild") { dependsOn(checkModerationModels) }

room3 {
    schemaDirectory("$projectDir/build/schemas")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(
            providers.gradleProperty("knit.warningsAsErrors").map(String::toBoolean).orElse(false),
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Persistence
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android)

    // Dependency injection
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // On-device ML runtimes
    implementation(libs.litert)

    // E2E encryption & QR
    implementation(libs.tink.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Offline APK sharing
    implementation(libs.reandroid.arsclib)
    implementation(libs.apksig)

    // Internet relay plane
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Voice calls & speech
    compileOnly(libs.webrtc.android)
    compileOnly(libs.androidx.core.telecom)
    debugImplementation(libs.webrtc.android)
    debugImplementation(libs.androidx.core.telecom)
    compileOnly(libs.moonshine.voice) { isTransitive = false }
    debugImplementation(libs.moonshine.voice) { isTransitive = false }
    compileOnly(libs.translate.native)
    if (translate) runtimeOnly(libs.translate.native)
    android.buildTypes.names.filter { it != "debug" }.forEach { buildType ->
        if (liveCallsInRelease) add("${buildType}RuntimeOnly", libs.webrtc.android)
        if (callsInRelease) add("${buildType}RuntimeOnly", libs.androidx.core.telecom)
        if (sttInRelease) {
            addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("${buildType}RuntimeOnly", libs.moonshine.voice) {
                isTransitive = false
            }
        }
    }
}
