package arx.core

import arx.display.ascii.AsciiCanvas
import arx.display.core.Image
import arx.display.core.SentinelAmgRef
import arx.display.core.SentinelImage
import arx.display.core.Shader
import com.typesafe.config.*
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.IllegalStateException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object Resources {
    val projectName: String? = System.getProperty("projectName")
    val projectSuffix = projectName?.let { "$it/" } ?: ""
    val baseAssetPath = System.getProperty("assetPath") ?: "src/main/resources/"
    val assetPath = baseAssetPath + projectSuffix
    val arxAssetPath = baseAssetPath + "arx/"
    private var images = ConcurrentHashMap<String, Image>()
    private var amgs = ConcurrentHashMap<String, AsciiCanvas>()
    private var shaders = ConcurrentHashMap<String, Shader>()
    private var typefaces = ConcurrentHashMap<String, ArxTypeface>()
    private var configs = ConcurrentHashMap<String, Config>()


    fun pickPath(str: String): File? {
        return if (str.startsWith("/") && Files.exists(Path.of(str))) {
            File(str)
        } else if (str.startsWith("/") && Files.exists(Path.of(str.substring(1)))) {
            File(str.substring(1))
        } else if (Files.exists(Path.of(assetPath + str))) {
            File(assetPath + str)
        } else if (Files.exists(Path.of(baseAssetPath + str))) {
            File(baseAssetPath + str)
        } else if (Files.exists(Path.of(arxAssetPath + str))) {
            File(arxAssetPath + str)
        } else {
            null
        }
    }

    fun pickResource(str: String): InputStream? {
        var stream = Resources::class.java.getResourceAsStream("/$projectSuffix$str")
        if (stream != null) {
            return stream
        }

        stream = Resources::class.java.getResourceAsStream("/$str")
        if (stream != null) {
            return stream
        }

        stream = Resources::class.java.getResourceAsStream("/arx/$str")
        return stream
    }

    fun pickResourceUrl(str: String): URL? {
        var url = Resources::class.java.getResource("/$projectSuffix$str")
        if (url != null) {
            return url
        }

        url = Resources::class.java.getResource("/$str")
        if (url != null) {
            return url
        }

        url = Resources::class.java.getResource("/arx/$str")
        return url
    }

    fun toRelativePath(f: File): String {
        return toRelativePath(f.absolutePath)
    }

    fun toRelativePath(absP: String): String {
        return if (absP.contains(assetPath)) {
            absP.substringAfter(assetPath)
        } else if (absP.contains(baseAssetPath)) {
            absP.substringAfter(baseAssetPath)
        } else if (absP.contains(arxAssetPath)) {
            absP.substringAfter(arxAssetPath)
        } else {
            Noto.warn("File $absP tried to convert to a relative path, but no relative path exists")
            absP
        }
    }

    fun pathsUnder(str: String) : List<String> {
        return pickPath(str)?.let { effPath ->
            effPath.listFiles()?.map { it.path } ?: emptyList()
        } ?: emptyList()
    }

    fun image(str: String): Image {
        return imageOpt(str) ?: Noto.errAndReturn("Could not find image: $str", SentinelImage)
    }

    fun imageOpt(str: String) : Image? {
        val raw = images.getOrPut(str) {
            pickPath(str)?.let {
                Image.load(it.absolutePath)
            } ?: inputStreamOpt(str)?.use { stream ->
                Image.load(stream, str)
            } ?: SentinelImage
        }
        if (raw == SentinelImage) {
            return null
        } else {
            return raw
        }
    }

    fun amg(str: String) : AsciiCanvas {
        return amgOpt(str) ?: Noto.errAndReturn("Could not find amg $str", SentinelAmgRef.toAmg())
    }

    fun amgOpt(str: String) : AsciiCanvas? {
        val raw = amgs.getOrPut(str) {
            inputStreamOpt(str)?.let { AsciiCanvas.readFrom(it) } ?: SentinelAmgRef.toAmg()
        }
        return if (raw == SentinelAmgRef.toAmg()) {
            null
        } else {
            raw
        }
    }


    fun shader(str: String): Shader = shaders.getOrPut(str) {
        val vertexSource = text("$str.vertex")!!
        val fragmentSource = text("$str.fragment")!!
        Shader(vertexSource, fragmentSource)
    }

    fun typeface(str: String): ArxTypeface = typefaces.getOrPut(str) {
        ArxTypeface.load(inputStream(str))
    }
    fun font(str: String, size: Int): ArxFont {
        return typeface(str).withSize(size)
    }


    object SmlIncluder : ConfigIncluder, ConfigIncluderClasspath {
        override fun withFallback(fallback: ConfigIncluder?): ConfigIncluder? {
            return this
        }

        override fun include(context: ConfigIncludeContext?, what: String?): ConfigObject {
            TODO("Not yet implemented")
        }

        override fun includeResources(context: ConfigIncludeContext, what: String): ConfigObject {
            if (pickPath(what)?.exists() == true) {
                return config(what).root()
            } else {
                val inferred = context.relativeTo(what)
                return inferred.parse(context.parseOptions())
            }
        }
    }

    fun config(str: String): Config {
        return configs.getOrPut(str) {
            val loaded = urlOpt(str)?.let {
                val c = ConfigFactory.parseURL(it, ConfigParseOptions.defaults().appendIncluder(SmlIncluder)).resolve()
                configs[str] = c
                c
            }
            if (loaded == null) {
                Noto.err("Could not load config $str")
                throw IllegalStateException("Could not find config $str")
            }
            loaded
        }
    }

    fun configOpt(str: String): Config? {
        val g = configs[str]
        if (g != null) {
            return g
        } else {
            val s = inputStreamOpt(str)
            return s?.bufferedReader()?.use {
                val c = ConfigFactory.parseReader(it, ConfigParseOptions.defaults().appendIncluder(SmlIncluder)).resolve()
                configs[str] = c

                c
            }
        }

    }

    fun inputStream(path: String): InputStream = inputStreamOpt(path)!!

    fun inputStreamOpt(path: String): InputStream? {
        val p = pickPath(path)
        return if (p != null) {
            FileInputStream(p)
        } else {
            return pickResource(path)
        }
    }

    fun urlOpt(path: String) : URL? {
        val p = pickPath(path)
        return if (p != null) {
            p.toURI().toURL()
        } else {
            return pickResourceUrl(path)
        }
    }

    fun text(path: String) : String? {
        return inputStreamOpt(path)?.bufferedReader()?.use { it.readText() }
    }
}