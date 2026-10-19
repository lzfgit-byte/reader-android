package com.ghs.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * 当前阶段的最小壳层：把引擎模块的真实输出直接显示出来。
 *
 * 目的不是做界面（界面见 `docs/02-architecture/ui-implementation.md`），
 * 而是证明 `:core:model` / `:core:engine` 能在 Android 运行时被调用。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EngineProbeScreen()
                }
            }
        }
    }
}

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

@Composable
private fun EngineProbeScreen() {
    val blocks = remember { PlainTextToBlocks.convert(SAMPLE_TEXT) }
    val scan = remember { InlineConfigScanner.scan(SAMPLE_CONFIG) }
    val validation = remember { InlineConfigScanner.validate(SAMPLE_CONFIG) }
    val scriptLines = remember { ScriptProbe.run() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GHS Mobile · 引擎探针", style = MaterialTheme.typography.headlineSmall)
        Text(
            "配置驱动的内容容器 · 当前为 M1 骨架",
            style = MaterialTheme.typography.bodyMedium,
        )

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
                    Text(
                        "无错误、无警告",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    issues.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
