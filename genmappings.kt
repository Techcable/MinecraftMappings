import com.google.common.collect.ImmutableBiMap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.opencsv.CSVReader
import net.techcable.srglib.FieldData
import net.techcable.srglib.JavaType
import net.techcable.srglib.MethodData
import net.techcable.srglib.format.MappingsFormat
import net.techcable.srglib.mappings.ImmutableMappings
import net.techcable.srglib.mappings.Mappings
import java.io.File
import java.io.IOException
import java.io.Reader
import java.net.URL
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

inline fun <reified T> Gson.fromJson(reader: Reader): T = this.fromJson<T>(reader, (object : TypeToken<T>() {}).type)
inline fun <reified T> Gson.fromJson(element: JsonElement): T = this.fromJson<T>(element, (object : TypeToken<T>() {}).type)
inline fun CSVReader.forEachLine(action: (Array<String>) -> Unit) {
    var line = this.readNext()
    while (line != null) {
        action(line)
        line = this.readNext()
    }
}

fun URL.loadJson(): JsonElement = this.openStream().reader().use { JsonParser().parse(it) }
fun URL.downloadTo(target: File) {
    check(target.createNewFile()) { "Unable to download ${this} to $target: File already exists" }
    this.openStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun JsonObject.set(key: String, value: JsonElement) = this.add(key, value)

fun stripDuplicates(mappings: Mappings): ImmutableMappings {
    val classes = HashMap<JavaType, JavaType>()
    val fields = HashMap<FieldData, FieldData>()
    val methods = HashMap<MethodData, MethodData>()
    mappings.forEachClass { original, renamed ->
        if (original != renamed) {
            classes[original] = renamed
        }
    }
    mappings.forEachField { original, renamed ->
        if (original.name != renamed.name) {
            fields[original] = renamed
        }
    }
    mappings.forEachMethod { original, renamed ->
        if (original.name != renamed.name) {
            methods[original] = renamed
        }
    }
    return ImmutableMappings.create(ImmutableBiMap.copyOf(classes), ImmutableBiMap.copyOf(methods), ImmutableBiMap.copyOf(fields))
}

fun downloadMcpMappings(srgMappings: Mappings, mappingsVersion: String): Mappings {
    val cacheFile = File("cache/mcp_$mappingsVersion/mcp.json")
    val fieldNames = HashMap<String, String>()
    val methodNames = HashMap<String, String>()
    if (!cacheFile.exists()) {
        cacheFile.parentFile.mkdirs()
        check(cacheFile.createNewFile())
        // Validate and compute the mapping version information
        val mappingsVersions: Map<String, Map<String, List<Int>>> = JsonReader(URL("http://export.mcpbot.bspk.rs/versions.json").openStream().reader()).use { reader ->
            val result = HashMap<String, MutableMap<String, MutableList<Int>>>()
            // We have to parse this by hand or else things don't work
            reader.beginObject()
            while (reader.hasNext()) {
                val version = reader.nextName()
                val byChannel = HashMap<String, MutableList<Int>>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val channelName = reader.nextName()
                    val channelVersions = ArrayList<Int>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        channelVersions.add(reader.nextInt())
                    }
                    reader.endArray()
                    byChannel[channelName] = channelVersions
                }
                reader.endObject()
                result[version] = byChannel
            }
            reader.endObject()
            result
        }
        val mappingsChannel = mappingsVersion.substring(0, mappingsVersion.indexOf('_'))
        val isNodoc = "_nodoc_" in mappingsVersion
        val fullMappingsChannel = if (isNodoc) mappingsChannel + "_nodoc" else mappingsChannel
        val mappingsId = mappingsVersion.substring(mappingsVersion.lastIndexOf('_') + 1).toInt()
        var minecraftVersion: String? = null
        for ((version, byChannel) in mappingsVersions.entries) {
            if (mappingsChannel !in byChannel) {
                System.err.println("Unknown channel $mappingsChannel for version $version")
                exitProcess(1)
            }
            if (mappingsId in byChannel[mappingsChannel]!!) {
                println("Found mappings $mappingsId for version $version")
                minecraftVersion = version
                break
            }
        }
        if (minecraftVersion == null) {
            System.err.println("Unable to find mappings: $mappingsVersion")
            exitProcess(1)
        }
        // Parse the mappings data files
        try {
            val url = URL("http://export.mcpbot.bspk.rs/mcp_$fullMappingsChannel/$mappingsId-$minecraftVersion/mcp_$fullMappingsChannel-$mappingsId-$minecraftVersion.zip")
            ZipInputStream(url.openStream()).use {
                var entry = it.nextEntry
                do {
                    when (entry.name) {
                        "fields.csv" -> {
                            CSVReader(it.reader()).forEachLine {
                                val original = it[0]
                                val renamed = it[1]
                                fieldNames[original] = renamed
                            }
                        }
                        "methods.csv" -> {
                            CSVReader(it.reader()).forEachLine {
                                val original = it[0]
                                val renamed = it[1]
                                methodNames[original] = renamed
                            }
                        }
                    }
                    entry = it.nextEntry
                } while (entry != null)
            }
            if (fieldNames.isEmpty() || methodNames.isEmpty()) {
                System.err.println("Unable to download MCP mappings $mappingsVersion: Unable to locate info in the zip file")
                exitProcess(1)
            }
        } catch (e: IOException) {
            System.err.println("Unable to download MCP mappings $mappingsVersion:")
            e.printStackTrace()
            exitProcess(1)
        }
        val json = JsonObject()
        json["fields"] = Gson().toJsonTree(fieldNames)
        json["methods"] = Gson().toJsonTree(methodNames)
        cacheFile.writeText(json.toString())
    } else {
        JsonReader(cacheFile.reader()).use {
            it.beginObject()
            while (it.hasNext()) {
                val name = it.nextName()
                if (name == "fields" || name == "methods") {
                    it.beginObject()
                    while (it.hasNext()) {
                        val original = it.nextName()
                        val renamed = it.nextString()
                        when (name) {
                            "fields" -> fieldNames[original] = renamed
                            "methods" -> methodNames[original] = renamed
                        }
                    }
                    it.endObject()
                }
            }
            it.endObject()
        }
    }
    return Mappings.createRenamingMappings(
            { oldType: JavaType -> oldType },
            { srgMethod: MethodData -> methodNames[srgMethod.name] ?: srgMethod.name },
            { srgField: FieldData -> fieldNames[srgField.name] ?: srgField.name }
    ).transform(srgMappings.inverted())
}

fun downloadSrgMappings(minecraftVersion: String): Mappings {
    val cacheFile = File("cache/mcp_joined.srg")
    if (!cacheFile.exists()) {
        cacheFile.parentFile.mkdirs()
        try {
            val url = URL("http://files.minecraftforge.net/maven/de/oceanlabs/mcp/mcp/$minecraftVersion/mcp-$minecraftVersion-srg.zip")
            ZipInputStream(url.openStream()).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name == "joined.srg") {
                        check(cacheFile.createNewFile())
                        cacheFile.outputStream().use { output -> zipStream.copyTo(output) }
                    }
                    entry = zipStream.nextEntry
                }
            }
            if (!cacheFile.exists()) {
                System.err.println("Unable to download SRG mappings for $minecraftVersion: Unable to locate joined.srg in the zip file")
                exitProcess(1)
            }
        } catch (e: IOException) {
            System.err.println("Unable to download SRG mappings for $minecraftVersion:")
            e.printStackTrace()
            exitProcess(1)
        }
    }
    return MappingsFormat.SEARGE_FORMAT.parseFile(cacheFile)
}

class CacheInfo(val buildDataCommits: MutableMap<String, String> = HashMap()) {
    fun saveTo(file: File) = file.writer().use { Gson().toJson(this, it) }

    companion object {
        fun loadFrom(file: File) = file.reader().use { Gson().fromJson<CacheInfo>(it) }
    }
}

/**
 * Get the build data commit from the specified spigot revision
 *
 * The Spigot revision is specified using '--rev=1.8' as a buildtools option
 * Available revisions: https://hub.spigotmc.org/versions/
 * @param spigotVersion the build data revision
 * @return the build data commit-id for this revision
 */
fun getBuildDataCommit(spigotVersion: String): String {
    val cacheFile = File("cache/info.json")
    val cacheInfo = if (cacheFile.exists()) CacheInfo.loadFrom(cacheFile) else CacheInfo()
    return cacheInfo.buildDataCommits.getOrElse(spigotVersion) {
        val url = URL("https://hub.spigotmc.org/versions/$spigotVersion.json")
        val json = url.loadJson().asJsonObject
        val buildDataCommit = json.getAsJsonObject("refs").get("BuildData").asString
        // Store it in the cache
        cacheInfo.buildDataCommits[spigotVersion] = buildDataCommit
        cacheInfo.saveTo(cacheFile)
        buildDataCommit
    }
}

fun downloadSpigotMappings(buildDataCommit: String): Mappings {
    val baseUrl = "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/browse/"
    val cacheDir = File("cache/spigot_$buildDataCommit")
    val classMappingsFile = File(cacheDir, "classes.csrg")
    val memberMappingsFile = File(cacheDir, "members.csrg")
    val packageMappingsFile = File(cacheDir, "packages.json")
    if (!classMappingsFile.exists() || !memberMappingsFile.exists() || !packageMappingsFile.exists()) {
        cacheDir.mkdirs()
        val info = URL("$baseUrl/info.json?at=$buildDataCommit&raw").loadJson().asJsonObject
        val classMappingsLocation = info.get("classMappings").asString
        val memberMappingsLocation = info.get("memberMappings").asString
        val packageMappingsLocation = info.get("packageMappings").asString
        if (!classMappingsFile.exists()) {
            URL("$baseUrl/mappings/$classMappingsLocation/?at=$buildDataCommit&raw").downloadTo(classMappingsFile)
        }
        if (!memberMappingsFile.exists()) {
            URL("$baseUrl/mappings/$memberMappingsLocation/?at=$buildDataCommit&raw").downloadTo(memberMappingsFile)
        }
        if (!packageMappingsFile.exists()) {
            val packages = HashMap<String, String>()
            for (line in URL("$baseUrl/mappings/$packageMappingsLocation/?at=$buildDataCommit&raw").openStream().bufferedReader().lineSequence()) {
                if (line.trim().startsWith("#") || line.isEmpty()) {
                    continue
                }
                val split = line.trim().split(" ")
                var original = split[0]
                var renamed = split[1]
                if (!original.endsWith("/") || !renamed.endsWith("/")) {
                    throw RuntimeException("Not a package: $line")
                }
                // Strip trailing '/'
                original = original.substring(0, original.length - 1)
                renamed = renamed.substring(0, renamed.length - 1)
                // Strip leading '.' if present
                if (original.startsWith(".")) {
                    original = original.substring(1)
                }
                if (renamed.startsWith(".")) {
                    renamed = renamed.substring(1)
                }
                original = original.replace('/', '.')
                renamed = renamed.replace('/', '.')
                packages[original] = renamed
            }
            JsonWriter(packageMappingsFile.writer()).use {
                it.beginObject()
                for ((original, renamed) in packages) {
                    it.name(original)
                    it.value(renamed)
                }
                it.endObject()
            }
        }
    }
    val classMappings = MappingsFormat.COMPACT_SEARGE_FORMAT.parseFile(classMappingsFile)
    val memberMappings = MappingsFormat.COMPACT_SEARGE_FORMAT.parseFile(memberMappingsFile)
    val packages = JsonReader(packageMappingsFile.reader()).use {
        val builder = ImmutableMap.builder<String, String>()
        it.beginObject()
        while (it.hasNext()) {
            builder.put(it.nextName(), it.nextString())
        }
        it.endObject()
        builder.build()
    }
    return Mappings.chain(ImmutableList.of(classMappings, memberMappings, Mappings.createPackageMappings(packages)))
}

const val USAGE = "Usage: ./genmappings.sh <minecraft version> [mcp version]"
val AVAILABLE_VERSIONS = setOf("1.8", "1.8.8", "1.9", "1.9.4", "1.10", "1.10.2")

fun main(args: Array<String>) {
    if ("--help" in args) {
        println("MinecraftMappings -- Finds minecraft mapping information from Spigot and MCP")
        println("Dependencies: SrgLib, OpenCSV, Gson, Guava")
        println(USAGE)
        println("Arguments:")
        println("\tminecraft version -- The minecraft version to generate the mappings for")
        println("\tmcp version -- The mcpbot mappings version to use (if any)")
        exitProcess(0)
    }
    if (args.size > 2) {
        System.err.println("Too many arguments: ${args.size}")
        System.err.println(USAGE)
        exitProcess(1)
    } else if (args.isEmpty()) {
        System.err.println("Insufficent arguments!")
        System.err.println(USAGE)
        exitProcess(1)
    }
    val minecraftVersion = args[0]
    val mcpVersion = args[1]
    if (minecraftVersion !in AVAILABLE_VERSIONS) {
        System.err.println("Unknown minecraft version: $minecraftVersion")
        exitProcess(1)
    }
    val outputFolder = File(minecraftVersion)
    if (!outputFolder.mkdirs()) {
        System.err.println("Mapping information for $minecraftVersion already exists!")
        exitProcess(1)
    }

    val srgMappings = downloadSrgMappings(minecraftVersion)
    val mcpMappings = downloadMcpMappings(srgMappings, mcpVersion)

    val buildDataCommit = getBuildDataCommit(minecraftVersion)
    val spigotMappings = downloadSpigotMappings(buildDataCommit)

    fun Mappings.writeTo(file: String) {
        val lines = MappingsFormat.SEARGE_FORMAT.toLines(stripDuplicates(this))
        lines.sort()
        File(outputFolder, file).bufferedWriter().use {
            for (line in lines) {
                it.write(line)
                it.write("\n") // Unix newlines FTW!
            }
        }
    }

    //
    // Write MCP mappings
    //

    srgMappings.writeTo("obf2srg.srg")
    srgMappings.inverted().writeTo("srg2obf.srg")
    mcpMappings.writeTo("srg2mcp.srg")
    mcpMappings.inverted().writeTo("mcp2srg.srg")
    val obf2mcp = Mappings.chain(ImmutableList.of(srgMappings, mcpMappings))
    obf2mcp.writeTo("obf2mcp.srg")
    obf2mcp.inverted().writeTo("mcp2obf.srg")

    //
    // Write Spigot mappings
    //

    spigotMappings.writeTo("obf2spigot.srg")
    spigotMappings.inverted().writeTo("spigot2obf.srg")

    //
    // Write Spigot <-> MCP
    //

    val spigot2srg = Mappings.chain(ImmutableList.of(spigotMappings.inverted(), srgMappings))
    val spigot2mcp = Mappings.chain(ImmutableList.of(spigot2srg, mcpMappings))
    spigot2srg.writeTo("spigot2srg.srg")
    spigot2srg.inverted().writeTo("srg2spigot.srg")
    spigot2mcp.writeTo("spigot2mcp.srg")
    spigot2mcp.inverted().writeTo("mcp2spigot.srg")
}
