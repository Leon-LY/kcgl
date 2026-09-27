import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import globals from 'globals'

// 前端 lint 基线；M1 起追加：@intlify/eslint-plugin-vue-i18n（文案 key 校验）
// 与 no-restricted-syntax 拦截裸 new Date（JST 时区纪律，docs/01 7.8）。
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    // .vue 的 script 块不受 tseslint 的 no-undef 豁免覆盖，需显式声明浏览器全局
    languageOptions: {
      globals: { ...globals.browser },
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    // JST 时区纪律（docs/01 7.8）：服务端时间为 naive JST 字符串，
    // 解析/格式化一律经 utils/format 出口；裸 new Date(参数) 与 Date.parse 时区语义不明，直接拦截。
    files: ['src/**/*.{ts,vue}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "NewExpression[callee.name='Date'][arguments.length>0]",
          message: '裸 new Date(...) 时区语义不明确。请使用 utils/format 的 JST 帮助函数（dayjs.tz 解析）。',
        },
        {
          selector: "CallExpression[callee.object.name='Date'][callee.property.name='parse']",
          message: 'Date.parse 按本地时区与实现相关规则解析。请使用 utils/format 的 JST 帮助函数。',
        },
      ],
    },
  },
)
