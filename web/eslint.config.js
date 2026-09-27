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
)
