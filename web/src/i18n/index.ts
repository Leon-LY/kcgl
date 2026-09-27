import { createI18n } from 'vue-i18n'
import ja from './locales/ja-JP.json'
import zh from './locales/zh-CN.json'
import en from './locales/en-US.json'

// 日文为系统默认（docs/01 7.8：用户以日本人为主）；用户级持久化在 M1 登录后接入。
// 文案命名空间随里程碑扩展：common/entry/scan/item/yahoo/stocktake/admin/print/errors。
export const i18n = createI18n({
  legacy: false,
  locale: 'ja-JP',
  fallbackLocale: 'ja-JP',
  messages: {
    'ja-JP': ja,
    'zh-CN': zh,
    'en-US': en,
  },
})
