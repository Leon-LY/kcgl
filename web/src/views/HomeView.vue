<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <section class="home">
    <p class="home-message">
      {{ t('home.preparing') }}
    </p>
  </section>
</template>

<style scoped>
.home {
  padding: 48px 16px;
  text-align: center;
  color: var(--kcgl-color-text-sub);
}
</style>
