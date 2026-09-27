import { watch } from 'vue'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { Locale } from 'vant'
import jaVant from 'vant/es/locale/lang/ja-JP'
import zhVant from 'vant/es/locale/lang/zh-CN'
import enVant from 'vant/es/locale/lang/en-US'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'

// Vant 组件内置文案（选择器确认/取消等）随应用语言联动（D-029：三语全量同步切换）。
Locale.use('ja-JP', jaVant)
Locale.use('zh-CN', zhVant)
Locale.use('en-US', enVant)
Locale.use(i18n.global.locale.value)
watch(i18n.global.locale, (locale) => {
  Locale.use(locale)
})

createApp(App).use(createPinia()).use(router).use(i18n).mount('#app')
