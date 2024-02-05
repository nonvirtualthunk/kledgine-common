package arx.core

import com.typesafe.config.ConfigFactory

import java.io.File

object AppConfig {

    fun localAppPath(appName: String) : File {
        val path = File("${System.getProperty("user.home")}/.conf/$appName/")
        if (! path.parentFile.exists()) {
            path.parentFile.mkdirs()
        }
        return path
    }

    fun confPath(appName: String, className: String) : File {
        val path = localAppPath(appName).resolve(className)
        if (! path.parentFile.exists()) {
            path.parentFile.mkdirs()
        }
        return path
    }

    inline fun <reified T> save (v: T, appName: String = System.getProperty("projectName") ?: "default") {
        val path = confPath(appName, T::class.simpleName ?: "")
        val content = serializeToConfig(v)
        path.writeText(content)
    }

    inline fun <reified T> load(appName: String = System.getProperty("projectName") ?: "default") : T {
        val path = confPath(appName, T::class.simpleName ?: "")
        val conf = if (! path.exists()) {
            ConfigFactory.parseString("{}")
        } else {
            ConfigFactory.parseFile(path)
        }
        return deserializeFromConfig(conf)
    }
}

object AppCache {

    fun cachePath(appName: String, path: String) : File {
        val fullPath = AppConfig.localAppPath(appName).resolve(path)
        if (! fullPath.parentFile.exists()) {
            fullPath.parentFile.mkdirs()
        }
        return fullPath
    }

    inline fun <reified T> cache (v: T, path: String, appName: String = System.getProperty("projectName") ?: "default") {
        val fullPath = cachePath(appName, path)
        val content = serializeToConfig(v)
        fullPath.writeText(content)
    }

    inline fun <reified T> load(path: String, appName: String = System.getProperty("projectName") ?: "default") : T? {
        val fullPath = cachePath(appName, path)
        val conf = if (! fullPath.exists()) {
            return null
        } else {
            ConfigFactory.parseFile(fullPath)
        }
        return deserializeFromConfig(conf)
    }
}