package me.leon

import java.util.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.leon.support.*
import org.junit.jupiter.api.Test

class NodeCrawler {
    companion object {
        private val nodeInfo = "$ROOT/info.md"
        val nodeInfoLocal = "$ROOT/info2.md"
        private val adConfig = "$ROOT/ad.txt"
        private val adReplaceConfig = "$ROOT/adreplace.txt"
        const val customInfo = "防失效github.com/Leon406 "
        private var subCount = 0
        private var nodeCount = 0
        val REG_AD by lazy { adConfig.toFile().readLines().joinToString("|").toRegex() }
        val REG_AD_REPLACE by lazy {
            adReplaceConfig.toFile().readLines().joinToString("|").toRegex().also { println(it) }
        }
    }

    private val maps = linkedMapOf<String, LinkedHashSet<Sub>>()

    /** 1.爬取配置文件对应链接的节点,并去重 2.同时进行可用性测试 tcping */
    @Test
    fun crawl() {
        // 1.爬取配置文件的订阅
        crawlNodes()
        checkNodes()
        nodeGroup()
        //        IpFilterTest().reTestFailIps()
    }

    /** 爬取配置文件数据，并去重写入文件 */
    @Test
    fun crawlNodes() {
        val subs1 = "$ROOT/pool/subpool".readLines()
        val subs2 = "$ROOT/pool/subs".readLines()
        val unavailable = "$ROOT/pool/unavailable".readLines()
        //        val subs3 = "$SHARE2/tmp".readLines()
        val sublist = "$ROOT/pool/sublists".readLines()
        val subs3 =
            sublist
                .map { it.readFromNet() }
                .flatMap { it.split("\r\n|\n".toRegex()) }
                .distinct()
                .also { println("before ${it.size}") }
                .filterNot { unavailable.contains(it) || it.startsWith("#") || it.trim().isEmpty() }
                .also {
                    println(it)
                    println("after ${it.size}")
                }
        val subs = (subs1 + subs2 + subs3).filterNot { unavailable.contains(it) }.toHashSet()

        POOL.writeLine()
        runBlocking {
            subs
                .filterNot { it.trim().startsWith("#") || it.trim().isEmpty() }
                .also { println("共有订阅源：${it.size.also { subCount = it }}") }
                .map { sub ->
                    sub to
                        async(DISPATCHER) {
                            runCatching {
                                Parser.parseFromSub(sub).also { println("$sub ${it.size} ") }
                            }
                                .getOrElse {
                                    println("___parse failed $sub  ${it.message}")
                                    linkedSetOf()
                                }
                        }
                }
                .map { it.first to it.second.await() }
                .fold(linkedSetOf<Sub>()) { acc, linkedHashSet ->
                    maps[linkedHashSet.first] = linkedHashSet.second
                    acc.apply { acc.addAll(linkedHashSet.second) }
                }
                .sortedBy {
                    it
                        .apply {
                            name =
                                name.removeFlags()
                                    .replace(REG_AD, "")
                                    .replace(REG_AD_REPLACE, customInfo)
                        }
                        .toUri()
                }
                .also {
                    POOL.writeLine(
                        it.also { nodeCount = it.size }.joinToString("\n") { it.toUri() }
                    )
                }
        }
    }

    /** 节点可用性测试 */
    @Test
    fun checkNodes() {
        nodeInfo.writeLine()
        // 2.筛选可用节点
        NODE_OK.writeLine()
        val ok: HashSet<Sub>
        runBlocking {
            ok =
                Parser.parseFromSub(POOL)
                    .map { it to async(DISPATCHER) { it.SERVER.quickConnect(it.serverPort, 2000) } }
                    .filter { it.second.await() > -1 }
                    .also {
                        println(
                            "有效节点: ${it.size}".also {
                                nodeInfo.writeLine("更新时间${timeStamp()}\r\n")
                                nodeInfo.writeLine("**总订阅: $subCount**")
                                nodeInfo.writeLine("**总节点: $nodeCount**")
                                nodeInfo.writeLine("**$it**")
                            }
                        )
                    }
                    .map { it.first }
                    .toHashSet()
                    .also { NODE_OK.writeLine(it.joinToString("\n") { it.toUri() }) }
        }

        println("节点分布: ")
        maps.forEach { (t, u) -> (ok - (ok - u)).also { println("$t ${it.size}/${u.size}") } }
    }

    fun nodeGroup() {
        NODE_SS.writeLine()
        NODE_SSR.writeLine()
        NODE_V2.writeLine()
        NODE_TR.writeLine()

        Parser.parseFromSub(NODE_OK).groupBy { it.javaClass }.forEach { (clazz, subList) ->
            subList.firstOrNull()?.run { name = customInfo + name }
            val data = subList.joinToString("\n") { it.toUri() }.b64Encode()
            writeData(clazz, data, subList)
        }
    }

    private fun writeData(clazz: Class<Sub>, data: String, subList: List<Sub>) {
        when (clazz) {
            SS::class.java ->
                NODE_SS.writeLine(data).also {
                    println("ss节点: ${subList.size}".also { nodeInfo.writeLine("- $it") })
                }
            SSR::class.java ->
                NODE_SSR.writeLine(data).also {
                    println("ssr节点: ${subList.size}".also { nodeInfo.writeLine("- $it") })
                }
            V2ray::class.java ->
                NODE_V2.writeLine(data).also {
                    println("v2ray节点: ${subList.size}".also { nodeInfo.writeLine("- $it") })
                }
            Trojan::class.java ->
                NODE_TR.writeLine(data).also {
                    println("trojan节点: ${subList.size}".also { nodeInfo.writeLine("- $it") })
                }
        }
    }
}
