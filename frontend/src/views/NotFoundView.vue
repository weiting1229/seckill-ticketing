<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="404" sub-title="找不到這個頁面">
    <template #extra>
      <el-button type="primary" @click="router.push('/events')">回活動列表</el-button>
    </template>
  </el-result>
</template>
