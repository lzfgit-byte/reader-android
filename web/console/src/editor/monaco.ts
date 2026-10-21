/**
 * Monaco 初始化：只引编辑器核心 + TypeScript 语言服务（不引 60+ 语言包）。
 *
 * 智能提示不是手写 provider，而是把 `config-api.d.ts` 交给 TypeScript 语言服务，
 * 一次拿到补全 / hover / 签名 / 诊断四类能力（ADR-0014）。
 */
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import 'monaco-editor/esm/vs/editor/editor.all'
import 'monaco-editor/esm/vs/language/typescript/monaco.contribution'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import tsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker'
// 配置规范的派生物：随包本地打包，不联网获取
import configApiDts from '../../config-api.d.ts?raw'

let initialized = false

export function setupMonaco(): typeof monaco {
  if (initialized) return monaco
  initialized = true

  // worker 必须本地化：APK 里没有 CDN，也保证离线可用
  self.MonacoEnvironment = {
    getWorker(_workerId: string, label: string) {
      if (label === 'typescript' || label === 'javascript') return new tsWorker()
      return new editorWorker()
    },
  }

  const defaults = monaco.languages.typescript.javascriptDefaults
  defaults.setCompilerOptions({
    target: monaco.languages.typescript.ScriptTarget.ES2020,
    allowNonTsExtensions: true,
    moduleResolution: monaco.languages.typescript.ModuleResolutionKind.NodeJs,
    module: monaco.languages.typescript.ModuleKind.ESNext,
    noEmit: true,
    checkJs: true,
    allowJs: true,
    strict: false,
  })
  defaults.setDiagnosticsOptions({
    noSemanticValidation: false,
    noSyntaxValidation: false,
  })

  defaults.addExtraLib(configApiDts, 'ts:config-api.d.ts')

  return monaco
}

export function createEditor(container: HTMLElement, value: string): monaco.editor.IStandaloneCodeEditor {
  return monaco.editor.create(container, {
    value,
    language: 'javascript',
    theme: 'vs-dark',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    tabSize: 2,
    insertSpaces: true,
    scrollBeyondLastLine: false,
    renderLineHighlight: 'line',
    fixedOverflowWidgets: true,
  })
}

export { monaco }
