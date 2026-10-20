package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.engine.config.InlineConfigScanner
import com.ghs.mobile.core.engine.text.PlainTextToBlocks
import com.ghs.mobile.core.model.BlockType
import com.ghs.mobile.core.script.ScriptProbe

/**
 * 引擎自检页：文本解析 / 脚本运行时 / 配置预检。
 *
 * 它不是产品界面，用来在设备上快速确认引擎各层是否正常（排查问题时很有用）。
 */
private val SAMPLE_TEXT = """
    第一段正文，含实体 A&amp;B。
    第二段<span>不应被当成标签</span>

    <img src="https://example.com/pic.jpg"> 图片之后的第三段
""".trimIndent()

private val SAMPLE_CONFIG = """
    const getData = (): WebConfig => /* break */ ({
      key: 'demo',
      name: 'Demo',
      homeUrl: 'https://example.com',
      getUrlReplace($) { return []; },
      getItems($) { return []; },
      getPagination($) { return []; },
      getTags($) { return []; },
      async getDetailInfo(item, cheerio) { return null; },
      adapterLoadUrl(url) { return url; },
      adapterSearchUrl(key) { return this.homeUrl + key; },
    });
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeScreen(onBack: () -> Unit) {
    val blocks = remember { PlainTextToBlocks.convert(SAMPLE_TEXT) }
    val scan = remember { InlineConfigScanner.scan(SAMPLE_CONFIG) }
    val validation = remember { InlineConfigScanner.validate(SAMPLE_CONFIG) }
    val scriptLines = remember { ScriptProbe.run() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("引擎自检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("配置驱动的内容容器 · 引擎自检", style = MaterialTheme.typography.titleMedium)

                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("文本 → 结构块", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "块数：${blocks.size}（段落 ${blocks.count { it.type == BlockType.P }}，" +
                                "图片 ${blocks.count { it.type == BlockType.IMAGE }}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        blocks.forEachIndexed { index, block ->
                            val label = if (block.type == BlockType.P) "P" else "IMG"
                            val body = if (block.type == BlockType.P) block.text else block.src
                            Text(
                                "[$index] $label · $body",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("脚本运行时 · QuickJS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "共 ${scriptLines.size} 项 · 通过 ${scriptLines.count { it.ok == true }} · " +
                                "失败 ${scriptLines.count { it.ok == false }}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        scriptLines.forEach { line ->
                            val mark = when (line.ok) {
                                true -> "✓"
                                false -> "✗"
                                null -> "·"
                            }
                            Text(
                                "$mark ${line.label}：${line.value}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("配置预检", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "key=${scan.key} · 方法 ${scan.methods.size} 个 · " +
                                "错误 ${validation.errors.size} · 警告 ${validation.warnings.size}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val issues = (validation.errors + validation.warnings).map { it.errorCode.code }
                        if (issues.isEmpty()) {
                            Text("无错误、无警告", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        } else {
                            issues.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}
